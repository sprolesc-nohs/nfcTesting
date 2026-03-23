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
import android.webkit.WebViewClient;
import java.nio.charset.StandardCharsets;

public class NFCReaderActivity extends Activity {

    private NfcAdapter nfcAdapter;
    private WebView webView;
    private boolean pageLoaded = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        webView = new WebView(this);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);

        // Wait until the page is fully loaded before calling any JS
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                pageLoaded = true;
                // If the activity was launched by tapping a tag, handle it now
                handleIntent(getIntent());
            }
        });

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

        // Register NDEF and catch-all filters so the system gives the tag
        // to THIS activity instead of showing the default Android NFC popup
        PendingIntent pending = PendingIntent.getActivity(
            this, 0,
            new Intent(this, getClass()).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE
        );

        IntentFilter ndefFilter = new IntentFilter(NfcAdapter.ACTION_NDEF_DISCOVERED);
        try {
            ndefFilter.addDataType("*/*");
        } catch (IntentFilter.MalformedMimeTypeException e) {
            // fallback: filter without mime type still works for text records
        }
        IntentFilter tagFilter = new IntentFilter(NfcAdapter.ACTION_TAG_DISCOVERED);

        IntentFilter[] filters = new IntentFilter[]{ ndefFilter, tagFilter };
        nfcAdapter.enableForegroundDispatch(this, pending, filters, null);
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
        setIntent(intent);
        handleIntent(intent);
    }

    /**
     * Reads NDEF text from the NFC tag carried by the intent
     * and sends it to the web page via JS.
     */
    private void handleIntent(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (!NfcAdapter.ACTION_NDEF_DISCOVERED.equals(action)
                && !NfcAdapter.ACTION_TAG_DISCOVERED.equals(action)
                && !NfcAdapter.ACTION_TECH_DISCOVERED.equals(action)) {
            return;
        }

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

            callJS("onTagScanned", text);
        }
    }

    /**
     * Calls a JavaScript function in the WebView on the UI thread.
     * Queues the call if the page hasn't loaded yet.
     */
    private void callJS(String functionName, String value) {
        if (!"onTagScanned".equals(functionName) && !"onStatus".equals(functionName)) return;
        String safe = value.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\");
        String script = functionName + "('" + safe + "')";

        if (!pageLoaded) {
            // Page not ready — retry after a short delay
            webView.postDelayed(() -> callJS(functionName, value), 300);
            return;
        }
        webView.post(() -> webView.evaluateJavascript(script, null));
    }
}
