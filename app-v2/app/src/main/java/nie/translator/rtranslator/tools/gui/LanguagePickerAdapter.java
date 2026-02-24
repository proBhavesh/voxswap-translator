package nie.translator.rtranslator.tools.gui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Comparator;

import nie.translator.rtranslator.R;
import nie.translator.rtranslator.tools.CustomLocale;

public class LanguagePickerAdapter extends RecyclerView.Adapter<LanguagePickerAdapter.ViewHolder> {

    public interface OnLanguageSelectedListener {
        void onLanguageSelected(CustomLocale locale);
    }

    private final ArrayList<CustomLocale> allLanguages;
    private ArrayList<CustomLocale> filteredLanguages;
    private CustomLocale selectedLanguage;
    private OnLanguageSelectedListener listener;
    private boolean allowDeselection = false;

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
        ImageView checkIcon;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            languageName = itemView.findViewById(R.id.languageName);
            checkIcon = itemView.findViewById(R.id.checkIcon);
        }
    }
}
