package app.tvmouse.phone;

import android.content.Context;
import android.widget.EditText;

/**
 * Casella di testo il cui cursore resta sempre in fondo: ogni modifica avviene quindi alla fine
 * del testo e può essere inviata alla TV come "cancella N caratteri, poi scrivi X".
 */
final class BufferEditText extends EditText {

    BufferEditText(Context context) {
        super(context);
    }

    @Override
    protected void onSelectionChanged(int selStart, int selEnd) {
        super.onSelectionChanged(selStart, selEnd);
        if (getText() == null) return; // chiamato durante la costruzione
        int len = length();
        if (selStart != len || selEnd != len) setSelection(len);
    }
}
