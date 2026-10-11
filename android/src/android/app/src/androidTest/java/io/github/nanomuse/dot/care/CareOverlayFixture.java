package io.github.nanomuse.dot.care;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Parcel;
import android.os.PersistableBundle;
import android.os.ResultReceiver;
import android.util.AtomicFile;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import org.json.JSONObject;

/** External test-APK process: only platform Java dependencies. Opaque backups stay on device.
 * Protocol is intentionally limited to the same installed framework, owner and source identity. */
public final class CareOverlayFixture extends Activity {
    private Intent pending;
    private TextView result;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(24, 100, 24, 24);
        layout.setBackgroundColor(Color.WHITE);
        result = new TextView(this);
        result.setText("CARE_TOUCH_PENDING");
        Button target = new Button(this);
        target.setText("CARE_TOUCH_TARGET");
        target.setContentDescription("CARE_TOUCH_TARGET");
        target.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { result.setText("CARE_TOUCH_RECEIVED"); }
        });
        layout.addView(target);
        layout.addView(result);
        setContentView(layout);
        pending = getIntent();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        pending = intent;
        // A resumed host already has focus; a newly foregrounded host waits for the callback.
        if (hasWindowFocus()) executePending();
    }

    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (focused) executePending();
    }

    private static String uuid(String value) {
        if (value == null || !UUID.fromString(value).toString().equals(value))
            throw new IllegalArgumentException("invalid owner/request");
        return value;
    }

    private void executePending() {
        if (pending == null) return;
        Intent command = pending;
        pending = null;
        String op = command.getStringExtra("careOp");
        if (op == null) return; // Legacy plain host launch remains inert.
        Bundle status = new Bundle();
        ResultReceiver receiver = command.getParcelableExtra("careReply", ResultReceiver.class);
        try {
            String owner = uuid(command.getStringExtra("careOwner"));
            String request = uuid(command.getStringExtra("careRequest"));
            String source = command.getStringExtra("careSource");
            if (source == null || !source.matches("[0-9a-f]{40}")) throw new IllegalArgumentException();
            if (!Arrays.asList("smoke", "backup", "restore").contains(op)) throw new IllegalArgumentException();
            status.putString("owner", owner);
            status.putString("request", request);
            status.putString("source", source);
            status.putString("operation", op);
            status.putBoolean("hostWindowFocused", hasWindowFocus());
            status.putLong("targetLastUpdateTime", getPackageManager()
                    .getPackageInfo("io.github.nanodot.app", 0).lastUpdateTime);
            status.putInt("hostPid", android.os.Process.myPid());
            File directory = new File(getCacheDir(), "care-native-" + owner);
            if (!directory.isDirectory() && !directory.mkdir()) throw new IllegalStateException();
            File receipt = new File(directory, "result-" + request + ".json");
            if (receipt.exists()) throw new IllegalStateException(); // Never overwrite evidence.
            try {
                if ("smoke".equals(op)) result.setText("CARE_TOUCH_PENDING");
                if ("backup".equals(op)) backup(directory, owner, source);
                if ("restore".equals(op)) restore(directory, owner, source);
                status.putBoolean("ok", true);
            } catch (Exception failure) {
                // Never include an exception message, ClipData text, MIME, label or size.
                status.putBoolean("ok", false);
                status.putString("error", "opaque_operation_failed");
            }
            JSONObject json = new JSONObject();
            for (String key : status.keySet()) json.put(key, status.get(key));
            write(new AtomicFile(receipt), json.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception failure) {
            status.putBoolean("ok", false);
            status.putString("error", "protocol_or_receipt_failed");
        }
        if (receiver != null) receiver.send(status.getBoolean("ok") ? 0 : 1, status);
    }

    private static void write(AtomicFile file, byte[] bytes) throws Exception {
        FileOutputStream stream = null;
        try {
            stream = file.startWrite();
            stream.write(bytes);
            file.finishWrite(stream);
        } catch (Exception failure) {
            if (stream != null) file.failWrite(stream);
            throw failure;
        }
    }

    private static byte[] parcel(ClipData clip) {
        Parcel parcel = Parcel.obtain();
        try {
            clip.writeToParcel(parcel, 0);
            if (parcel.hasFileDescriptors()) throw new IllegalArgumentException();
            return parcel.marshall(); // Binder objects are rejected by framework marshalling.
        } finally { parcel.recycle(); }
    }

    private static void safeExtras(PersistableBundle extras) {
        if (extras == null) return;
        for (String key : extras.keySet()) {
            Object value = extras.get(key);
            if (value instanceof PersistableBundle) safeExtras((PersistableBundle) value);
            else if (value != null && !(value instanceof String || value instanceof Boolean
                    || value instanceof Integer || value instanceof Long || value instanceof Double
                    || value instanceof String[] || value instanceof boolean[] || value instanceof int[]
                    || value instanceof long[] || value instanceof double[])) throw new IllegalArgumentException();
        }
    }

    private static void supported(ClipData clip) {
        if (clip == null) return;
        ClipDescription description = clip.getDescription();
        if (description.getLabel() != null && !(description.getLabel() instanceof String))
            throw new IllegalArgumentException();
        // Completed classifier scores have no public enumerable key API. Refuse before Copy.
        if (description.getClassificationStatus() == ClipDescription.CLASSIFICATION_COMPLETE)
            throw new IllegalArgumentException();
        safeExtras(description.getExtras());
        for (int index = 0; index < clip.getItemCount(); index++) {
            ClipData.Item item = clip.getItemAt(index);
            if ((item.getText() != null && !(item.getText() instanceof String))
                    || item.getUri() != null || item.getIntent() != null || item.getTextLinks() != null
                    || item.getIntentSender() != null) throw new IllegalArgumentException();
        }
        // Rebuild with the original description/items to reject inaccessible extra ClipData fields
        // (e.g. a hidden bitmap icon). This comparison is in RAM only, never emitted or hashed.
        ClipData rebuilt = new ClipData(description, clip.getItemAt(0));
        for (int index = 1; index < clip.getItemCount(); index++) rebuilt.addItem(clip.getItemAt(index));
        if (!Arrays.equals(parcel(clip), parcel(rebuilt))) throw new IllegalArgumentException();
    }

    private byte[] encode(String owner, String source, ClipData clip) {
        Parcel data = Parcel.obtain();
        try {
            data.writeInt(1);
            data.writeString(owner);
            data.writeString(source);
            data.writeString(Build.FINGERPRINT);
            data.writeInt(clip == null ? 0 : 1);
            if (clip != null) clip.writeToParcel(data, 0);
            if (data.hasFileDescriptors()) throw new IllegalArgumentException();
            return data.marshall();
        } finally { data.recycle(); }
    }

    private ClipData decode(byte[] bytes, String owner, String source) {
        Parcel data = Parcel.obtain();
        try {
            data.unmarshall(bytes, 0, bytes.length);
            data.setDataPosition(0);
            if (data.readInt() != 1 || !owner.equals(data.readString())
                    || !source.equals(data.readString()) || !Build.FINGERPRINT.equals(data.readString()))
                throw new IllegalArgumentException();
            int present = data.readInt();
            if (present != 0 && present != 1) throw new IllegalArgumentException();
            ClipData clip = present == 0 ? null : ClipData.CREATOR.createFromParcel(data);
            if (data.dataAvail() != 0) throw new IllegalArgumentException();
            supported(clip);
            return clip;
        } finally { data.recycle(); }
    }

    private void backup(File directory, String owner, String source) throws Exception {
        File base = new File(directory, "clipboard.opaque");
        if (base.exists() || new File(directory, "clipboard.opaque.new").exists()
                || new File(directory, "clipboard.opaque.bak").exists()) throw new IllegalStateException();
        ClipboardManager clipboard = getSystemService(ClipboardManager.class);
        ClipData original = clipboard.getPrimaryClip(); // Only after actual focused host callback.
        if ((original != null) != clipboard.hasPrimaryClip()) throw new IllegalStateException();
        supported(original);
        byte[] bytes = encode(owner, source, original);
        ClipData decoded = decode(bytes, owner, source);
        if (original != null && !Arrays.equals(parcel(original), parcel(decoded)))
            throw new IllegalStateException();
        AtomicFile file = new AtomicFile(base);
        write(file, bytes);
        if (!Arrays.equals(bytes, file.readFully())) throw new IllegalStateException();
        decode(file.readFully(), owner, source); // Acknowledgement only after durable readback.
    }

    private static boolean extrasEqual(PersistableBundle a, PersistableBundle b) {
        if (a == null || b == null) return a == b;
        if (!a.keySet().equals(b.keySet())) return false;
        for (String key : a.keySet()) {
            Object x = a.get(key), y = b.get(key);
            if (x instanceof PersistableBundle && y instanceof PersistableBundle) {
                if (!extrasEqual((PersistableBundle) x, (PersistableBundle) y)) return false;
            } else if (!Objects.deepEquals(x, y)) return false;
        }
        return true;
    }

    private static boolean payloadEqual(ClipData a, ClipData b) {
        if (a == null || b == null) return a == b;
        supported(b);
        if (a.getItemCount() != b.getItemCount()) return false;
        ClipDescription x = a.getDescription(), y = b.getDescription();
        // Only ClipDescription.timestamp is excluded: ClipboardService rewrites it on set.
        if (!Objects.equals(x.getLabel(), y.getLabel()) || x.getMimeTypeCount() != y.getMimeTypeCount()
                || x.isStyledText() != y.isStyledText()
                || x.getClassificationStatus() != y.getClassificationStatus()
                || !extrasEqual(x.getExtras(), y.getExtras())) return false;
        for (int i = 0; i < x.getMimeTypeCount(); i++)
            if (!x.getMimeType(i).equals(y.getMimeType(i))) return false;
        for (int i = 0; i < a.getItemCount(); i++) {
            if (!Objects.equals(a.getItemAt(i).getText(), b.getItemAt(i).getText())
                    || !Objects.equals(a.getItemAt(i).getHtmlText(), b.getItemAt(i).getHtmlText())) return false;
        }
        return true;
    }

    private void restore(File directory, String owner, String source) throws Exception {
        AtomicFile file = new AtomicFile(new File(directory, "clipboard.opaque"));
        // Missing/corrupt/wrong-owner backup is a failure, never a guessed empty clipboard.
        ClipData original = decode(file.readFully(), owner, source);
        ClipboardManager clipboard = getSystemService(ClipboardManager.class);
        if (original == null) clipboard.clearPrimaryClip(); else clipboard.setPrimaryClip(original);
        if (!payloadEqual(original, clipboard.getPrimaryClip())
                || clipboard.hasPrimaryClip() != (original != null)) throw new IllegalStateException();
        // Durable readback receipt precedes deletion, so interruption is explicitly recoverable.
        JSONObject restored = new JSONObject().put("owner", owner).put("source", source)
                .put("payloadRestored", true).put("timestampExact", false);
        write(new AtomicFile(new File(directory, "clipboard-restored.json")),
                restored.toString().getBytes(StandardCharsets.UTF_8));
        file.delete();
        if (file.getBaseFile().exists() || new File(directory, "clipboard.opaque.new").exists()
                || new File(directory, "clipboard.opaque.bak").exists()) throw new IllegalStateException();
    }
}
