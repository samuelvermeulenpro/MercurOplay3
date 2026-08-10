package fr.svpro.radiomercure.contact;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.TextUtils;
import android.util.Patterns;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import fr.svpro.radiomercure.R;
import fr.svpro.radiomercure.util.Config;

public class ContactActivity extends AppCompatActivity {

    /** Mime types accepted for the optional attachment, per the spec: images, PDF, MS Office, LibreOffice, plain text. */
    private static final String[] ALLOWED_ATTACHMENT_MIME_TYPES = {
            "image/*",
            "application/pdf",
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.ms-excel",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.ms-powerpoint",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "application/vnd.oasis.opendocument.text",
            "application/vnd.oasis.opendocument.spreadsheet",
            "application/vnd.oasis.opendocument.presentation",
            "text/plain",
    };

    private TextInputLayout layoutName;
    private TextInputLayout layoutEmail;
    private TextInputLayout layoutSubject;
    private TextInputLayout layoutMessage;
    private TextInputEditText editName;
    private TextInputEditText editEmail;
    private TextInputEditText editPhone;
    private AutoCompleteTextView dropdownSubject;
    private TextInputEditText editMessage;
    private TextView textAttachmentLabel;
    private ImageButton buttonRemoveAttachment;

    private ActivityResultLauncher<String[]> attachmentPicker;
    @Nullable
    private Uri attachmentUri;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_contact);

        layoutName = findViewById(R.id.layoutName);
        layoutEmail = findViewById(R.id.layoutEmail);
        layoutSubject = findViewById(R.id.layoutSubject);
        layoutMessage = findViewById(R.id.layoutMessage);
        editName = findViewById(R.id.editName);
        editEmail = findViewById(R.id.editEmail);
        editPhone = findViewById(R.id.editPhone);
        dropdownSubject = findViewById(R.id.dropdownSubject);
        editMessage = findViewById(R.id.editMessage);
        textAttachmentLabel = findViewById(R.id.textAttachmentLabel);
        buttonRemoveAttachment = findViewById(R.id.buttonRemoveAttachment);
        ImageButton buttonBack = findViewById(R.id.buttonBack);
        MaterialButton buttonSubmit = findViewById(R.id.buttonSubmit);

        buttonBack.setOnClickListener(v -> finish());

        ArrayAdapter<String> subjectAdapter = new ArrayAdapter<>(
                this, android.R.layout.simple_list_item_1, getResources().getStringArray(R.array.contact_subjects));
        dropdownSubject.setAdapter(subjectAdapter);
        dropdownSubject.setOnClickListener(v -> dropdownSubject.showDropDown());

        attachmentPicker = registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
            if (uri == null) return;
            attachmentUri = uri;
            try {
                getContentResolver().takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (SecurityException ignored) {
                // Not all providers support persistable permissions; we only need the
                // grant for the immediate outgoing email intent below, which still works.
            }
            textAttachmentLabel.setText(queryFileName(uri));
            buttonRemoveAttachment.setVisibility(android.view.View.VISIBLE);
        });

        findViewById(R.id.rowAttachmentPicker).setOnClickListener(v ->
                attachmentPicker.launch(ALLOWED_ATTACHMENT_MIME_TYPES));

        buttonRemoveAttachment.setOnClickListener(v -> clearAttachment());

        buttonSubmit.setOnClickListener(v -> submit());
    }

    private void clearAttachment() {
        attachmentUri = null;
        textAttachmentLabel.setText(R.string.contact_field_attachment);
        buttonRemoveAttachment.setVisibility(android.view.View.GONE);
    }

    private String queryFileName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    String name = cursor.getString(index);
                    if (name != null && !name.isEmpty()) return name;
                }
            }
        } catch (Exception ignored) {
            // Fall through to the generic label below.
        }
        return getString(R.string.contact_field_attachment);
    }

    private void submit() {
        String name = textOf(editName);
        String email = textOf(editEmail);
        String phone = textOf(editPhone);
        String subject = dropdownSubject.getText() != null ? dropdownSubject.getText().toString().trim() : "";
        String message = textOf(editMessage);

        boolean valid = true;

        if (TextUtils.isEmpty(name)) {
            layoutName.setError(getString(R.string.contact_error_required));
            valid = false;
        } else {
            layoutName.setError(null);
        }

        if (TextUtils.isEmpty(email)) {
            layoutEmail.setError(getString(R.string.contact_error_required));
            valid = false;
        } else if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            layoutEmail.setError(getString(R.string.contact_error_email_invalid));
            valid = false;
        } else {
            layoutEmail.setError(null);
        }

        boolean subjectKnown = false;
        for (String candidate : getResources().getStringArray(R.array.contact_subjects)) {
            if (candidate.equals(subject)) {
                subjectKnown = true;
                break;
            }
        }
        if (!subjectKnown) {
            layoutSubject.setError(getString(R.string.contact_error_subject_required));
            valid = false;
        } else {
            layoutSubject.setError(null);
        }

        if (TextUtils.isEmpty(message)) {
            layoutMessage.setError(getString(R.string.contact_error_required));
            valid = false;
        } else {
            layoutMessage.setError(null);
        }

        if (!valid) return;

        sendEmail(name, email, phone, subject, message);
    }

    private void sendEmail(String name, String email, String phone, String subject, String message) {
        StringBuilder body = new StringBuilder();
        body.append(getString(R.string.contact_label_name)).append(" : ").append(name).append('\n');
        body.append(getString(R.string.contact_label_email)).append(" : ").append(email).append('\n');
        if (!TextUtils.isEmpty(phone)) {
            body.append(getString(R.string.contact_label_phone)).append(" : ").append(phone).append('\n');
        }
        body.append(getString(R.string.contact_label_subject)).append(" : ").append(subject).append("\n\n");
        body.append(message);
        body.append(getString(R.string.contact_signature));

        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("message/rfc822");
        intent.putExtra(Intent.EXTRA_EMAIL, new String[]{Config.CONTACT_EMAIL});
        intent.putExtra(Intent.EXTRA_SUBJECT, getString(R.string.contact_email_subject_format, subject));
        intent.putExtra(Intent.EXTRA_TEXT, body.toString());
        if (attachmentUri != null) {
            intent.putExtra(Intent.EXTRA_STREAM, attachmentUri);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        }

        try {
            startActivity(Intent.createChooser(intent, getString(R.string.contact_send_chooser_title)));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.contact_no_email_app, Toast.LENGTH_LONG).show();
        }
    }

    private String textOf(TextInputEditText editText) {
        return editText.getText() != null ? editText.getText().toString().trim() : "";
    }
}
