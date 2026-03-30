package nie.translator.vtranslator.tools.gui;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import androidx.appcompat.app.AlertDialog;
import nie.translator.vtranslator.R;

public class GuiTools {
    public static AlertDialog createDialog(Activity activity, String title, String text) {
        final AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setTitle(title);
        builder.setMessage(text);
        builder.setPositiveButton(android.R.string.ok, null);

        return builder.create();
    }

    public static int getColor(Context context, int colorCode) {
        return context.getResources().getColor(colorCode, null);
    }

    public static ColorStateList getColorStateList(Context context, int colorCode) {
        return context.getResources().getColorStateList(colorCode, null);
    }
}
