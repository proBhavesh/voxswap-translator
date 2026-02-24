package nie.translator.rtranslator.access;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.progressindicator.LinearProgressIndicator;

import nie.translator.rtranslator.R;

public class ModelListAdapter extends RecyclerView.Adapter<ModelListAdapter.ViewHolder> {
    public static final int STATUS_NOT_DOWNLOADED = 0;
    public static final int STATUS_DOWNLOADING = 1;
    public static final int STATUS_TRANSFERRING = 2;
    public static final int STATUS_DOWNLOADED = 3;
    public static final int STATUS_VERIFYING = 4;
    public static final int STATUS_ERROR = 5;

    private final String[] names;
    private final int[] sizesKb;
    private final int[] statuses;
    private final int[] progresses;

    public ModelListAdapter(String[] names, int[] sizesKb) {
        this.names = names;
        this.sizesKb = sizesKb;
        this.statuses = new int[names.length];
        this.progresses = new int[names.length];
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_model_row, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        String displayName = names[position].replace(".onnx", "").replace("_", " ");
        holder.modelName.setText(displayName);

        float sizeMb = sizesKb[position] / 1000f;
        if (sizeMb >= 1) {
            holder.modelSize.setText(String.format("%.0f MB", sizeMb));
        } else {
            holder.modelSize.setText(String.format("%d KB", sizesKb[position]));
        }

        switch (statuses[position]) {
            case STATUS_DOWNLOADED:
                holder.modelStatus.setText(R.string.status_downloaded);
                holder.modelStatus.setTextColor(holder.itemView.getContext().getColor(R.color.status_success));
                holder.modelProgress.setVisibility(View.GONE);
                break;
            case STATUS_DOWNLOADING:
                holder.modelStatus.setText(R.string.status_downloading);
                holder.modelStatus.setTextColor(holder.itemView.getContext().getColor(R.color.brand_primary));
                holder.modelProgress.setVisibility(View.VISIBLE);
                holder.modelProgress.setProgress(progresses[position]);
                break;
            case STATUS_TRANSFERRING:
                holder.modelStatus.setText(R.string.status_transferring);
                holder.modelStatus.setTextColor(holder.itemView.getContext().getColor(R.color.brand_primary));
                holder.modelProgress.setVisibility(View.VISIBLE);
                holder.modelProgress.setIndeterminate(true);
                break;
            case STATUS_VERIFYING:
                holder.modelStatus.setText(R.string.status_verifying);
                holder.modelStatus.setTextColor(holder.itemView.getContext().getColor(R.color.text_secondary));
                holder.modelProgress.setVisibility(View.VISIBLE);
                holder.modelProgress.setIndeterminate(true);
                break;
            case STATUS_ERROR:
                holder.modelStatus.setText(R.string.status_error);
                holder.modelStatus.setTextColor(holder.itemView.getContext().getColor(R.color.status_error));
                holder.modelProgress.setVisibility(View.GONE);
                break;
            default:
                holder.modelStatus.setText(R.string.status_not_downloaded);
                holder.modelStatus.setTextColor(holder.itemView.getContext().getColor(R.color.text_muted));
                holder.modelProgress.setVisibility(View.GONE);
                break;
        }
    }

    @Override
    public int getItemCount() {
        return names.length;
    }

    public void updateStatus(int index, int status) {
        if (index >= 0 && index < statuses.length) {
            statuses[index] = status;
            notifyItemChanged(index);
        }
    }

    public void updateProgress(int index, int progress) {
        if (index >= 0 && index < progresses.length) {
            progresses[index] = progress;
            notifyItemChanged(index);
        }
    }

    public void updateStatusAndProgress(int index, int status, int progress) {
        if (index >= 0 && index < statuses.length) {
            statuses[index] = status;
            progresses[index] = progress;
            notifyItemChanged(index);
        }
    }

    public int getStatus(int index) {
        return statuses[index];
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        final TextView modelName;
        final TextView modelSize;
        final TextView modelStatus;
        final LinearProgressIndicator modelProgress;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            modelName = itemView.findViewById(R.id.modelName);
            modelSize = itemView.findViewById(R.id.modelSize);
            modelStatus = itemView.findViewById(R.id.modelStatus);
            modelProgress = itemView.findViewById(R.id.modelProgress);
        }
    }
}
