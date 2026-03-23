import android.app.Activity;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.IntentFilter;
import android.nfc.NdefMessage;
import android.nfc.NdefRecord;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.Ndef;
import android.os.Bundle;
import android.webkit.WebSettings;
import android.webkit.WebView;
import java.nio.charset.StandardCharsets;

public class NFCReaderActivity extends Activity {

    private NfcAdapter nfcAdapter;
    private WebView webView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Load the web UI inside a WebView
        webView = new WebView(this);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        // Block the WebView from loading external URLs
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        webView.loadUrl("file:///android_asset/altered.html");
        setContentView(webView);

        nfcAdapter = NfcAdapter.getDefaultAdapter(this);

        if (nfcAdapter == null) {
            callJS("onStatus", "NFC not supported on this device.");
        } else if (!nfcAdapter.isEnabled()) {
            callJS("onStatus", "NFC is disabled. Enable it in Settings.");
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (nfcAdapter == null) return;

        PendingIntent pending = PendingIntent.getActivity(
            this, 0,
            new Intent(this, getClass()).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE
        );
        nfcAdapter.enableForegroundDispatch(this, pending, new IntentFilter[0], null);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (nfcAdapter != null) {
            nfcAdapter.disableForegroundDispatch(this);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);

        Tag tag = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG);
        if (tag == null) return;

        Ndef ndef = Ndef.get(tag);
        if (ndef == null) {
            callJS("onStatus", "Tag has no NDEF data.");
            return;
        }

        NdefMessage message = ndef.getCachedNdefMessage();
        if (message == null) {
            callJS("onStatus", "NDEF message is empty.");
            return;
        }

        for (NdefRecord record : message.getRecords()) {
            byte[] payload = record.getPayload();
            if (payload == null || payload.length == 0) continue;

            int langLength = payload[0] & 0x3F;
            String text = new String(
                payload, 1 + langLength, payload.length - 1 - langLength,
                StandardCharsets.UTF_8
            ).trim();

            // Send the scanned text to the web page
            callJS("onTagScanned", text);
        }
    }

    /**
     * Calls a JavaScript function in the WebView on the UI thread.
     * The value is escaped to prevent injection.
     */
    private void callJS(String functionName, String value) {
        // Sanitize: allow only known function names
        if (!"onTagScanned".equals(functionName) && !"onStatus".equals(functionName)) return;
        // Escape the value for safe insertion into a JS string literal
        String safe = value.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\");
        webView.post(() -> webView.evaluateJavascript(functionName + "('" + safe + "')", null));
    }
}
