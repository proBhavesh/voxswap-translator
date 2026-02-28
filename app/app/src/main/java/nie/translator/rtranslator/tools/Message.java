package nie.translator.rtranslator.tools;

import android.content.Context;
import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.Nullable;

public class Message implements Parcelable {
    private String text;
    private String textToTranslate;
    @Nullable
    private Sender sender;

    public Message(Context context, String text) {
        this.text = text;
        this.textToTranslate = text;
        this.sender = null;
    }

    public Message(String textToTranslate, Context context, String text) {
        this.textToTranslate = textToTranslate;
        this.text = text;
        this.sender = null;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public String getTextToTranslate() {
        return textToTranslate;
    }

    @Nullable
    public Sender getSender() {
        return sender;
    }

    public static class Sender {
        private String name;

        public Sender(String name) {
            this.name = name;
        }

        public String getName() {
            return name;
        }
    }

    public static final Creator<Message> CREATOR = new Creator<Message>() {
        @Override
        public Message createFromParcel(Parcel in) {
            return new Message(in);
        }

        @Override
        public Message[] newArray(int size) {
            return new Message[size];
        }
    };

    private Message(Parcel in) {
        text = in.readString();
        textToTranslate = in.readString();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel parcel, int flags) {
        parcel.writeString(text);
        parcel.writeString(textToTranslate);
    }
}
