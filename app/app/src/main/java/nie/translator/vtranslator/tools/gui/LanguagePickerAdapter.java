package nie.translator.vtranslator.tools.gui;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;

import nie.translator.vtranslator.R;
import nie.translator.vtranslator.tools.CustomLocale;
import nie.translator.vtranslator.tools.VoiceDownloadManager;

public class LanguagePickerAdapter extends RecyclerView.Adapter<LanguagePickerAdapter.ViewHolder> {

    public interface OnLanguageSelectedListener {
        void onLanguageSelected(CustomLocale locale);
    }

    public enum VoiceStatus {
        READY,
        AVAILABLE,
        NOT_AVAILABLE,
        NOT_APPLICABLE
    }

    private final ArrayList<CustomLocale> allLanguages;
    private ArrayList<CustomLocale> filteredLanguages;
    private CustomLocale selectedLanguage;
    private OnLanguageSelectedListener listener;
    private boolean allowDeselection = false;

    /* Pre-computed voice status per language code — avoids File.exists() during scrolling */
    private Map<String, VoiceStatus> voiceStatusMap = null;

    public LanguagePickerAdapter(ArrayList<CustomLocale> languages, CustomLocale selectedLanguage) {
        this.allLanguages = new ArrayList<>(languages);
        this.allLanguages.sort(Comparator.comparing(CustomLocale::getDisplayNameWithoutTTS));
        this.filteredLanguages = new ArrayList<>(this.allLanguages);
        this.selectedLanguage = selectedLanguage;
    }

    public LanguagePickerAdapter(ArrayList<CustomLocale> languages, CustomLocale selectedLanguage, boolean allowDeselection) {
        this(languages, selectedLanguage);
        this.allowDeselection = allowDeselection;
    }

    public void setOnLanguageSelectedListener(OnLanguageSelectedListener listener) {
        this.listener = listener;
    }

    /**
     * Enable voice status indicators for target language pickers.
     * Pre-computes status for all languages so onBindViewHolder does zero I/O.
     */
    public void setShowVoiceStatus(Context context) {
        voiceStatusMap = new HashMap<>();
        for (CustomLocale locale : allLanguages) {
            String langCode = locale.getLanguage();
            if (VoiceDownloadManager.isVoiceDownloaded(context, langCode)) {
                voiceStatusMap.put(langCode, VoiceStatus.READY);
            } else if (VoiceDownloadManager.isVoiceAvailable(langCode)) {
                voiceStatusMap.put(langCode, VoiceStatus.AVAILABLE);
            } else {
                voiceStatusMap.put(langCode, VoiceStatus.NOT_AVAILABLE);
            }
        }
        notifyDataSetChanged();
    }

    public void filter(String query) {
        if (query == null || query.trim().isEmpty()) {
            filteredLanguages = new ArrayList<>(allLanguages);
        } else {
            String lowerQuery = query.toLowerCase().trim();
            filteredLanguages = new ArrayList<>();
            for (CustomLocale locale : allLanguages) {
                String displayName = locale.getDisplayNameWithoutTTS().toLowerCase();
                String code = locale.getCode().toLowerCase();
                if (displayName.contains(lowerQuery) || code.contains(lowerQuery)) {
                    filteredLanguages.add(locale);
                }
            }
        }
        notifyDataSetChanged();
    }

    public CustomLocale getSelectedLanguage() {
        return selectedLanguage;
    }

    public void setSelectedLanguage(CustomLocale language) {
        this.selectedLanguage = language;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_language_row, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        CustomLocale locale = filteredLanguages.get(position);
        holder.languageName.setText(locale.getDisplayNameWithoutTTS());

        boolean isSelected = locale.equals(selectedLanguage);
        holder.checkIcon.setVisibility(isSelected ? View.VISIBLE : View.GONE);

        /* Voice status indicator */
        if (voiceStatusMap != null) {
            VoiceStatus status = voiceStatusMap.get(locale.getLanguage());
            if (status == null) status = VoiceStatus.NOT_AVAILABLE;

            holder.voiceStatus.setVisibility(View.VISIBLE);
            switch (status) {
                case READY:
                    holder.voiceStatus.setText(R.string.voice_status_ready);
                    holder.voiceStatus.setTextColor(ContextCompat.getColor(holder.itemView.getContext(), R.color.status_success));
                    break;
                case AVAILABLE:
                    int sizeKB = VoiceDownloadManager.getVoiceModelSizeKB(locale.getLanguage());
                    int sizeMB = sizeKB / 1000;
                    holder.voiceStatus.setText(holder.itemView.getContext().getString(R.string.voice_status_size_mb, sizeMB));
                    holder.voiceStatus.setTextColor(ContextCompat.getColor(holder.itemView.getContext(), R.color.text_muted));
                    break;
                case NOT_AVAILABLE:
                    holder.voiceStatus.setText(R.string.voice_status_unavailable);
                    holder.voiceStatus.setTextColor(ContextCompat.getColor(holder.itemView.getContext(), R.color.text_muted));
                    break;
            }
        } else {
            holder.voiceStatus.setVisibility(View.GONE);
        }

        holder.itemView.setOnClickListener(v -> {
            if (allowDeselection && locale.equals(selectedLanguage)) {
                selectedLanguage = null;
            } else {
                selectedLanguage = locale;
            }
            notifyDataSetChanged();
            if (listener != null) {
                listener.onLanguageSelected(selectedLanguage);
            }
        });
    }

    @Override
    public int getItemCount() {
        return filteredLanguages.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView languageName;
        TextView voiceStatus;
        ImageView checkIcon;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            languageName = itemView.findViewById(R.id.languageName);
            voiceStatus = itemView.findViewById(R.id.voiceStatus);
            checkIcon = itemView.findViewById(R.id.checkIcon);
        }
    }
}
