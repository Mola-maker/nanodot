package android.speech;

/** Controlled platform stop boundary; Android's mockable jar supplies all other operations. */
public final class ControlledSpeechRecognizer extends SpeechRecognizer {
    public int stops;
    public boolean failStop;
    @Override public void stopListening() {
        stops++;
        if (failStop) throw new IllegalStateException("controlled stop failure");
    }
}
