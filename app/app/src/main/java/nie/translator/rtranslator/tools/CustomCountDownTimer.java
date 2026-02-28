package nie.translator.rtranslator.tools;

import android.os.CountDownTimer;

public abstract class CustomCountDownTimer {
    private CountDownTimer timer;
    private long millisInFuture;
    private long countDownInterval;

    public CustomCountDownTimer(long millisInFuture, long countDownInterval) {
        this.millisInFuture = millisInFuture;
        this.countDownInterval = countDownInterval;
        timer = new CountDownTimer(millisInFuture, countDownInterval) {
            @Override
            public void onTick(long millisUntilFinished) {
                CustomCountDownTimer.this.onTick(millisUntilFinished);
            }

            @Override
            public void onFinish() {
                CustomCountDownTimer.this.onFinish();
            }
        };
    }

    public abstract void onTick(long millisUntilFinished);

    public abstract void onFinish();

    public void start() {
        timer.start();
    }

    public void cancel() {
        timer.cancel();
    }
}
