#ifndef VOXSWAP_WIFI_HOTSPOT_H
#define VOXSWAP_WIFI_HOTSPOT_H

/**
 * Initialize WiFi hotspot. Configures the wireless interface, starts
 * hostapd (AP) and dnsmasq (DHCP), and verifies both are running.
 *
 * @param iface     Wireless interface name (e.g. "wlan0")
 * @param password  WPA2 password (NULL for default "voxswap123")
 * @return 0 on success, -1 on failure
 */
int wifi_hotspot_init(const char *iface, const char *password);

/**
 * Shut down hotspot: kill hostapd/dnsmasq, remove interface IP,
 * clean up generated config files. Safe to call if init was not called.
 */
void wifi_hotspot_shutdown(void);

#endif
