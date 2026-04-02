#include "wifi-hotspot.h"

#include <ctype.h>
#include <errno.h>
#include <signal.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

#define WIFI_IFACE_MAX        16
#define WIFI_SSID_MAX         32
#define WIFI_PASSWORD_MAX     64
#define WIFI_CONF_PATH_MAX    128

#define WIFI_DEFAULT_PASSWORD "voxswap123"
#define WIFI_GATEWAY_IP       "10.0.0.1"
#define WIFI_SUBNET_MASK      "255.255.255.0"
#define WIFI_DHCP_RANGE_START "10.0.0.100"
#define WIFI_DHCP_RANGE_END   "10.0.0.110"
#define WIFI_DHCP_LEASE_TIME  "12h"

#define WIFI_HOSTAPD_PATH     "/usr/sbin/hostapd"
#define WIFI_DNSMASQ_PATH     "/usr/bin/dnsmasq"
#define WIFI_IP_PATH          "/usr/sbin/ip"

/* nanosleep helper — 100ms intervals for polling waitpid */
#define POLL_INTERVAL_NS      100000000L   /* 100ms */
#define PROCESS_VERIFY_NS     200000000L   /* 200ms */
#define KILL_TIMEOUT_POLLS    20           /* 20 * 100ms = 2s */

typedef struct WifiHotspot {
    pid_t hostapd_pid;
    pid_t dnsmasq_pid;
    char iface[WIFI_IFACE_MAX];
    char ssid[WIFI_SSID_MAX];
    char password[WIFI_PASSWORD_MAX];
    char hostapd_conf_path[WIFI_CONF_PATH_MAX];
    char dnsmasq_conf_path[WIFI_CONF_PATH_MAX];
    bool iface_configured;
} WifiHotspot;

static WifiHotspot *g_hotspot = NULL;

/* --- Interface name validation --- */

/**
 * Validate a network interface name.
 * Must be 1-15 chars, alphanumeric plus hyphens only.
 * Prevents path traversal when building sysfs paths like
 * /sys/class/net/{iface}/address.
 */
static bool validate_iface_name(const char *iface)
{
    size_t len = strlen(iface);
    if (len == 0 || len >= WIFI_IFACE_MAX) {
        return false;
    }

    for (size_t i = 0; i < len; i++) {
        char c = iface[i];
        if (!isalnum((unsigned char)c) && c != '-') {
            return false;
        }
    }

    return true;
}

/* --- MAC address reading --- */

/**
 * Read the last 4 hex characters of the MAC address for the given interface.
 * Reads from /sys/class/net/{iface}/address (format: "aa:bb:cc:dd:ee:ff\n").
 * Writes uppercase hex suffix (e.g. "3FEC") to out.
 *
 * @return 0 on success, -1 on failure (caller should use fallback SSID)
 */
static int read_mac_suffix(const char *iface, char *out, size_t out_size)
{
    if (out_size < 5) {
        return -1;
    }

    char path[128];
    snprintf(path, sizeof(path), "/sys/class/net/%s/address", iface);

    FILE *f = fopen(path, "r");
    if (!f) {
        return -1;
    }

    /* MAC format: "aa:bb:cc:dd:ee:ff\n" (17 chars + newline) */
    char mac[32];
    if (!fgets(mac, sizeof(mac), f)) {
        fclose(f);
        return -1;
    }
    fclose(f);

    size_t mac_len = strlen(mac);
    /* Strip trailing newline */
    if (mac_len > 0 && mac[mac_len - 1] == '\n') {
        mac[mac_len - 1] = '\0';
        mac_len--;
    }

    /* Expected: "aa:bb:cc:dd:ee:ff" = 17 chars */
    if (mac_len < 17) {
        return -1;
    }

    /* Extract last 2 octets: positions 12,13 and 15,16
     * "aa:bb:cc:dd:ee:ff"
     *                ^^ ^^  */
    char a = mac[12];
    char b = mac[13];
    char c = mac[15];
    char d = mac[16];

    /* Validate hex */
    if (!isxdigit((unsigned char)a) || !isxdigit((unsigned char)b) ||
        !isxdigit((unsigned char)c) || !isxdigit((unsigned char)d)) {
        return -1;
    }

    out[0] = (char)toupper((unsigned char)a);
    out[1] = (char)toupper((unsigned char)b);
    out[2] = (char)toupper((unsigned char)c);
    out[3] = (char)toupper((unsigned char)d);
    out[4] = '\0';

    return 0;
}

/* --- Config file generation --- */

/**
 * Write hostapd configuration to the generated config path.
 * Template includes 5GHz 802.11ac, WPA2-PSK, and the derived SSID.
 */
static int write_hostapd_conf(const WifiHotspot *hs)
{
    FILE *f = fopen(hs->hostapd_conf_path, "w");
    if (!f) {
        fprintf(stderr, "wifi-hotspot: fopen(%s) failed: %s\n",
                hs->hostapd_conf_path, strerror(errno));
        return -1;
    }

    fprintf(f, "interface=%s\n", hs->iface);
    fprintf(f, "driver=nl80211\n");
    fprintf(f, "ssid=%s\n", hs->ssid);
    fprintf(f, "hw_mode=g\n");
    fprintf(f, "channel=6\n");
    fprintf(f, "country_code=US\n");
    fprintf(f, "\n");
    fprintf(f, "ieee80211n=1\n");
    fprintf(f, "wmm_enabled=1\n");
    fprintf(f, "\n");
    fprintf(f, "wpa=2\n");
    fprintf(f, "wpa_passphrase=%s\n", hs->password);
    fprintf(f, "wpa_key_mgmt=WPA-PSK\n");
    fprintf(f, "rsn_pairwise=CCMP\n");

    if (ferror(f)) {
        fprintf(stderr, "wifi-hotspot: write to %s failed\n", hs->hostapd_conf_path);
        fclose(f);
        return -1;
    }

    fclose(f);
    return 0;
}

/**
 * Write dnsmasq configuration to the generated config path.
 * Sets up DHCP on the AP interface with the VoxSwap subnet.
 */
static int write_dnsmasq_conf(const WifiHotspot *hs)
{
    FILE *f = fopen(hs->dnsmasq_conf_path, "w");
    if (!f) {
        fprintf(stderr, "wifi-hotspot: fopen(%s) failed: %s\n",
                hs->dnsmasq_conf_path, strerror(errno));
        return -1;
    }

    fprintf(f, "interface=%s\n", hs->iface);
    fprintf(f, "except-interface=lo\n");
    fprintf(f, "bind-dynamic\n");
    fprintf(f, "dhcp-range=%s,%s,%s,%s\n",
            WIFI_DHCP_RANGE_START, WIFI_DHCP_RANGE_END,
            WIFI_SUBNET_MASK, WIFI_DHCP_LEASE_TIME);
    fprintf(f, "dhcp-option=3,%s\n", WIFI_GATEWAY_IP);
    fprintf(f, "dhcp-option=6,%s\n", WIFI_GATEWAY_IP);
    fprintf(f, "no-resolv\n");
    fprintf(f, "no-hosts\n");
    fprintf(f, "log-dhcp\n");

    if (ferror(f)) {
        fprintf(stderr, "wifi-hotspot: write to %s failed\n", hs->dnsmasq_conf_path);
        fclose(f);
        return -1;
    }

    fclose(f);
    return 0;
}

/* --- Process management --- */

/**
 * Run a command synchronously (fork/exec + waitpid).
 * argv must be a NULL-terminated array. argv[0] is the executable path.
 *
 * @return 0 if process exited with status 0, -1 otherwise
 */
static int run_command(char *const argv[])
{
    pid_t pid = fork();
    if (pid == -1) {
        fprintf(stderr, "wifi-hotspot: fork() failed: %s\n", strerror(errno));
        return -1;
    }

    if (pid == 0) {
        /* Child: exec the command */
        execv(argv[0], argv);
        _exit(127);
    }

    /* Parent: wait for child to complete.
     * Retry on EINTR — a signal (e.g. SIGINT during init) can interrupt
     * the blocking waitpid, which is not a real failure. */
    int status;
    pid_t result;
    do {
        result = waitpid(pid, &status, 0);
    } while (result == -1 && errno == EINTR);

    if (result == -1) {
        fprintf(stderr, "wifi-hotspot: waitpid() failed: %s\n", strerror(errno));
        return -1;
    }

    if (WIFEXITED(status) && WEXITSTATUS(status) == 0) {
        return 0;
    }

    return -1;
}

/**
 * Fork/exec a long-running daemon process. Does not wait.
 *
 * @return child PID on success, -1 on fork failure
 */
static pid_t spawn_process(char *const argv[])
{
    pid_t pid = fork();
    if (pid == -1) {
        fprintf(stderr, "wifi-hotspot: fork() failed: %s\n", strerror(errno));
        return -1;
    }

    if (pid == 0) {
        /* Child: exec the daemon */
        execv(argv[0], argv);
        /* exec failed — exit with distinguishable code */
        _exit(127);
    }

    return pid;
}

/**
 * Send SIGTERM to a process, wait up to 2 seconds for exit,
 * then SIGKILL as last resort. Prevents zombie processes.
 */
static void kill_and_wait(pid_t pid, const char *name)
{
    if (pid <= 0) {
        return;
    }

    if (kill(pid, SIGTERM) == -1) {
        if (errno == ESRCH) {
            return;   /* Already dead */
        }
    }

    struct timespec poll_ts = {0, POLL_INTERVAL_NS};

    for (int i = 0; i < KILL_TIMEOUT_POLLS; i++) {
        int status;
        pid_t result = waitpid(pid, &status, WNOHANG);
        if (result == pid) {
            printf("wifi-hotspot: %s (pid %d) exited\n", name, (int)pid);
            return;
        }
        if (result == -1) {
            return;   /* Error — process doesn't exist */
        }
        nanosleep(&poll_ts, NULL);
    }

    /* Force kill after timeout */
    fprintf(stderr, "wifi-hotspot: %s (pid %d) did not exit, sending SIGKILL\n",
            name, (int)pid);
    kill(pid, SIGKILL);

    /* Retry on EINTR — SIGKILL guarantees the process will die, so this
     * waitpid will succeed once the kernel delivers it. A signal to us
     * (e.g. second Ctrl-C during shutdown) can interrupt the wait. */
    pid_t result;
    do {
        result = waitpid(pid, NULL, 0);
    } while (result == -1 && errno == EINTR);
}

/* --- Interface configuration --- */

/**
 * Configure the wireless interface: flush existing IPs, assign the
 * gateway IP, and bring the interface up.
 *
 * Uses fork/exec of `ip` command — readable, handles edge cases,
 * always available on Linux. Performance irrelevant for one-time init.
 */
static int configure_interface(const char *iface)
{
    printf("wifi-hotspot: configuring %s with IP %s/24\n", iface, WIFI_GATEWAY_IP);

    /* Remove any existing IPs on this interface */
    char *flush_argv[] = {
        WIFI_IP_PATH, "addr", "flush", "dev", (char *)iface, NULL
    };
    if (run_command(flush_argv) != 0) {
        fprintf(stderr, "wifi-hotspot: ip addr flush failed for %s\n", iface);
        return -1;
    }

    /* Assign the gateway IP */
    char ip_cidr[32];
    snprintf(ip_cidr, sizeof(ip_cidr), "%s/24", WIFI_GATEWAY_IP);

    char *add_argv[] = {
        WIFI_IP_PATH, "addr", "add", ip_cidr, "dev", (char *)iface, NULL
    };
    if (run_command(add_argv) != 0) {
        fprintf(stderr, "wifi-hotspot: ip addr add failed for %s\n", iface);
        return -1;
    }

    /* Bring interface up */
    char *up_argv[] = {
        WIFI_IP_PATH, "link", "set", (char *)iface, "up", NULL
    };
    if (run_command(up_argv) != 0) {
        fprintf(stderr, "wifi-hotspot: ip link set up failed for %s\n", iface);
        return -1;
    }

    return 0;
}

/**
 * Remove the gateway IP from the interface (cleanup on shutdown).
 * Best-effort — failure is logged but not fatal during shutdown.
 */
static void deconfigure_interface(const char *iface)
{
    char ip_cidr[32];
    snprintf(ip_cidr, sizeof(ip_cidr), "%s/24", WIFI_GATEWAY_IP);

    char *del_argv[] = {
        WIFI_IP_PATH, "addr", "del", ip_cidr, "dev", (char *)iface, NULL
    };
    if (run_command(del_argv) != 0) {
        fprintf(stderr, "wifi-hotspot: ip addr del failed for %s (non-fatal)\n", iface);
    }
}

/**
 * Verify a spawned process is still alive after a brief delay.
 * Catches immediate failures (config error, missing binary, etc.).
 *
 * @return 0 if process is still running, -1 if it died
 */
static int verify_process_alive(pid_t pid, const char *name)
{
    struct timespec verify_ts = {0, PROCESS_VERIFY_NS};
    nanosleep(&verify_ts, NULL);

    int status;
    pid_t result = waitpid(pid, &status, WNOHANG);

    if (result == 0) {
        return 0;   /* Still running */
    }

    if (result == pid) {
        if (WIFEXITED(status)) {
            fprintf(stderr, "wifi-hotspot: %s died immediately (exit code %d)\n",
                    name, WEXITSTATUS(status));
        } else if (WIFSIGNALED(status)) {
            fprintf(stderr, "wifi-hotspot: %s killed by signal %d\n",
                    name, WTERMSIG(status));
        }
        return -1;
    }

    fprintf(stderr, "wifi-hotspot: waitpid for %s failed: %s\n",
            name, strerror(errno));
    return -1;
}

/* --- Lifecycle --- */

/**
 * Initialize the WiFi hotspot.
 *
 * Sequence: validate → allocate → derive SSID → write configs →
 * configure interface → spawn hostapd → spawn dnsmasq → verify alive.
 *
 * Uses goto cleanup for sequential init failure — tears down whatever
 * was partially set up (kill spawned processes, deconfigure interface,
 * remove config files, free memory).
 */
int wifi_hotspot_init(const char *iface, const char *password)
{
    if (!iface) {
        fprintf(stderr, "wifi-hotspot: interface name is required\n");
        return -1;
    }

    if (!validate_iface_name(iface)) {
        fprintf(stderr, "wifi-hotspot: invalid interface name '%s' "
                "(must be 1-15 chars, alphanumeric/hyphens only)\n", iface);
        return -1;
    }

    WifiHotspot *hs = calloc(1, sizeof(WifiHotspot));
    if (!hs) {
        fprintf(stderr, "wifi-hotspot: calloc failed\n");
        return -1;
    }

    strncpy(hs->iface, iface, WIFI_IFACE_MAX - 1);
    hs->iface[WIFI_IFACE_MAX - 1] = '\0';

    if (password) {
        strncpy(hs->password, password, WIFI_PASSWORD_MAX - 1);
    } else {
        strncpy(hs->password, WIFI_DEFAULT_PASSWORD, WIFI_PASSWORD_MAX - 1);
    }
    hs->password[WIFI_PASSWORD_MAX - 1] = '\0';

    /* Derive SSID from MAC suffix — non-fatal if MAC read fails */
    char mac_suffix[8];
    if (read_mac_suffix(iface, mac_suffix, sizeof(mac_suffix)) == 0) {
        snprintf(hs->ssid, WIFI_SSID_MAX, "VoxSwap-%s", mac_suffix);
    } else {
        fprintf(stderr, "wifi-hotspot: could not read MAC for %s, using fallback SSID\n", iface);
        snprintf(hs->ssid, WIFI_SSID_MAX, "VoxSwap-0001");
    }

    snprintf(hs->hostapd_conf_path, WIFI_CONF_PATH_MAX, "/tmp/voxswap-hostapd.conf");
    snprintf(hs->dnsmasq_conf_path, WIFI_CONF_PATH_MAX, "/tmp/voxswap-dnsmasq.conf");

    printf("wifi-hotspot: SSID=%s iface=%s\n", hs->ssid, hs->iface);

    /* Write config files */
    if (write_hostapd_conf(hs) != 0) {
        fprintf(stderr, "wifi-hotspot: failed to write hostapd config\n");
        goto cleanup;
    }

    if (write_dnsmasq_conf(hs) != 0) {
        fprintf(stderr, "wifi-hotspot: failed to write dnsmasq config\n");
        goto cleanup;
    }

    /* Disconnect any active WiFi connection and tell NetworkManager to
     * stop managing the interface — otherwise it fights hostapd. */
    char *nm_disconnect_argv[] = {
        "/usr/bin/nmcli", "device", "disconnect", (char *)iface, NULL
    };
    (void)run_command(nm_disconnect_argv);

    char *nm_unmanage_argv[] = {
        "/usr/bin/nmcli", "device", "set", (char *)iface, "managed", "no", NULL
    };
    (void)run_command(nm_unmanage_argv);

    /* Disable WiFi power saving — iwlmvm power_scheme=2 (balanced) keeps
     * the radio sleeping, killing range. Set to 1 (CAM = always active).
     * Requires module reload since it's a load-time parameter. */
    (void)run_command((char *[]){"/usr/sbin/modprobe", "-r", "iwlmvm", NULL});
    (void)run_command((char *[]){"/usr/sbin/modprobe", "iwlmvm", "power_scheme=1", NULL});

    /* Wait for NM to fully release the interface and driver to reload */
    struct timespec settle_ts = {2, 0};
    nanosleep(&settle_ts, NULL);

    /* Configure interface before starting hostapd — it needs the IP assigned */
    if (configure_interface(hs->iface) != 0) {
        fprintf(stderr, "wifi-hotspot: failed to configure interface %s\n", hs->iface);
        goto cleanup;
    }
    hs->iface_configured = true;

    /* Start hostapd (runs in foreground by default) */
    char *hostapd_argv[] = {
        WIFI_HOSTAPD_PATH, hs->hostapd_conf_path, NULL
    };
    hs->hostapd_pid = spawn_process(hostapd_argv);
    if (hs->hostapd_pid == -1) {
        fprintf(stderr, "wifi-hotspot: failed to spawn hostapd\n");
        goto cleanup;
    }
    printf("wifi-hotspot: hostapd started (pid %d)\n", (int)hs->hostapd_pid);

    /* Verify hostapd is still alive after brief delay */
    if (verify_process_alive(hs->hostapd_pid, "hostapd") != 0) {
        hs->hostapd_pid = 0;   /* Already dead, don't try to kill in cleanup */
        goto cleanup;
    }

    /* Stop system dnsmasq if running — it holds port 53 and prevents
     * our instance from binding. Best-effort, failure is not fatal. */
    char *stop_dnsmasq_argv[] = {
        "/usr/bin/systemctl", "stop", "dnsmasq", NULL
    };
    (void)run_command(stop_dnsmasq_argv);

    /* Start dnsmasq (--keep-in-foreground = foreground without debug mode) */
    char conf_file_arg[WIFI_CONF_PATH_MAX + 16];
    snprintf(conf_file_arg, sizeof(conf_file_arg),
             "--conf-file=%s", hs->dnsmasq_conf_path);

    char *dnsmasq_argv[] = {
        WIFI_DNSMASQ_PATH, "--keep-in-foreground",
        conf_file_arg, NULL
    };
    hs->dnsmasq_pid = spawn_process(dnsmasq_argv);
    if (hs->dnsmasq_pid == -1) {
        fprintf(stderr, "wifi-hotspot: failed to spawn dnsmasq\n");
        goto cleanup;
    }
    printf("wifi-hotspot: dnsmasq started (pid %d)\n", (int)hs->dnsmasq_pid);

    /* Verify dnsmasq is still alive after brief delay */
    if (verify_process_alive(hs->dnsmasq_pid, "dnsmasq") != 0) {
        hs->dnsmasq_pid = 0;
        goto cleanup;
    }

    g_hotspot = hs;
    printf("wifi-hotspot: initialized (hostapd=%d dnsmasq=%d)\n",
           (int)hs->hostapd_pid, (int)hs->dnsmasq_pid);
    return 0;

cleanup:
    /* Tear down whatever was partially started */
    if (hs->dnsmasq_pid > 0) {
        kill_and_wait(hs->dnsmasq_pid, "dnsmasq");
    }
    if (hs->hostapd_pid > 0) {
        kill_and_wait(hs->hostapd_pid, "hostapd");
    }
    if (hs->iface_configured) {
        deconfigure_interface(hs->iface);
    }
    (void)remove(hs->dnsmasq_conf_path);
    (void)remove(hs->hostapd_conf_path);
    free(hs);
    return -1;
}

/**
 * Shut down the WiFi hotspot.
 * Kill hostapd and dnsmasq, remove interface IP, clean up config files.
 * Safe to call if init was not called or already shut down.
 */
void wifi_hotspot_shutdown(void)
{
    if (!g_hotspot) {
        return;
    }

    WifiHotspot *hs = g_hotspot;
    g_hotspot = NULL;

    /* Kill daemons — dnsmasq first (DHCP), then hostapd (AP) */
    kill_and_wait(hs->dnsmasq_pid, "dnsmasq");
    kill_and_wait(hs->hostapd_pid, "hostapd");

    /* Remove interface configuration */
    if (hs->iface_configured) {
        deconfigure_interface(hs->iface);
    }

    /* Clean up generated config files */
    (void)remove(hs->dnsmasq_conf_path);
    (void)remove(hs->hostapd_conf_path);

    /* Return interface to NetworkManager so WiFi reconnects after shutdown */
    char *nm_manage_argv[] = {
        "/usr/bin/nmcli", "device", "set", hs->iface, "managed", "yes", NULL
    };
    (void)run_command(nm_manage_argv);

    free(hs);
    printf("wifi-hotspot: shut down\n");
}
