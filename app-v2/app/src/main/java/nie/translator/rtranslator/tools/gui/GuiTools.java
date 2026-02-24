/*
 * Copyright 2016 Luca Martino.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copyFile of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package nie.translator.rtranslator.tools.gui;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import androidx.appcompat.app.AlertDialog;
import nie.translator.rtranslator.R;

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
