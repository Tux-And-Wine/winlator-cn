package com.winlator;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PointF;
import android.graphics.RectF;
import android.os.Bundle;
import android.net.Uri;
import android.util.Base64;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.PopupWindow;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.winlator.core.LocaleHelper;
import com.winlator.core.ImageUtils;
import com.winlator.contentdialog.ContentDialog;
import com.winlator.inputcontrols.Binding;
import com.winlator.inputcontrols.ControlElement;
import com.winlator.inputcontrols.ControlsProfile;
import com.winlator.inputcontrols.IconPackManager;
import com.winlator.inputcontrols.InputControlsManager;
import com.winlator.math.Mathf;
import com.winlator.core.AppUtils;
import com.winlator.core.FileUtils;
import com.winlator.core.UnitUtils;
import com.winlator.widget.ColorPickerView;
import com.winlator.widget.InputControlsView;
import com.winlator.widget.NumberPicker;
import com.winlator.widget.SeekBar;
import com.winlator.winhandler.MIDIHandler;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;

public class ControlsEditorActivity extends AppCompatActivity implements View.OnClickListener {
    private static final int ICON_SLOT_PRIMARY = 0;
    private static final int ICON_SLOT_SECONDARY = 1;
    private static final int OPEN_CUSTOM_ICON_REQUEST_CODE = 1001;
    private static final int IMPORT_ICON_PACK_REQUEST_CODE = 1002;
    private static final int EXPORT_ICON_PACK_REQUEST_CODE = 1003;
    private static final int CREATE_ICON_PACK_REQUEST_CODE = 1004;
    private static final Binding[] TRACKPAD_PRESS_BINDINGS = {
        Binding.NONE,
        Binding.MOUSE_LEFT_BUTTON,
        Binding.MOUSE_RIGHT_BUTTON,
        Binding.MOUSE_MIDDLE_BUTTON
    };
    private InputControlsView inputControlsView;
    private ControlsProfile profile;
    private IconPackManager iconPackManager;
    private View toolbox;
    private PopupWindow elementSettingsPopup;
    private ControlElement editingElement;
    private final LinearLayout[] editingIconLists = new LinearLayout[2];
    private final ImageView[] editingCustomIconPreviews = new ImageView[2];
    private final byte[] editingSelectedIconIds = new byte[2];
    private final String[] editingCustomIconData = {"", ""};
    private int editingIconPickerSlot = ICON_SLOT_PRIMARY;
    private String pendingExportPackId;
    private String pendingCreatePackName;

    @Override
    public void onCreate(Bundle bundle) {
        AppUtils.setActivityTheme(this);
        super.onCreate(bundle);
        AppUtils.hideSystemUI(this);
        setContentView(R.layout.controls_editor_activity);

        inputControlsView = new InputControlsView(this);
        inputControlsView.setEditMode(true);
        inputControlsView.setOverlayOpacity(0.6f);
        iconPackManager = new IconPackManager(this);

        profile = InputControlsManager.loadProfile(this, ControlsProfile.getProfileFile(this, getIntent().getIntExtra("profile_id", 0)));
        ((TextView)findViewById(R.id.TVProfileName)).setText(profile.getName());
        inputControlsView.setProfile(profile);

        FrameLayout container = findViewById(R.id.FLContainer);
        container.addView(inputControlsView, 0);

        container.findViewById(R.id.BTAddElement).setOnClickListener(this);
        container.findViewById(R.id.BTRemoveElement).setOnClickListener(this);
        container.findViewById(R.id.BTElementSettings).setOnClickListener(this);
        container.findViewById(R.id.BTIconPackManager).setOnClickListener(this);

        toolbox = container.findViewById(R.id.Toolbox);

        final PointF startPoint = new PointF();
        final boolean[] isActionDown = {false};
        container.findViewById(R.id.BTMove).setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    startPoint.x = event.getX();
                    startPoint.y = event.getY();
                    isActionDown[0] = true;
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (isActionDown[0]) {
                        float newX = toolbox.getX() + (event.getX() - startPoint.x);
                        float newY = toolbox.getY() + (event.getY() - startPoint.y);
                        moveToolbox(newX, newY);
                    }
                    break;
                case MotionEvent.ACTION_UP:
                    isActionDown[0] = false;
                    break;
            }
            return true;
        });
    }

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleHelper.setSystemLocale(newBase));
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshEditingIconList();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == OPEN_CUSTOM_ICON_REQUEST_CODE && resultCode == Activity.RESULT_OK && data != null && data.getData() != null) {
            if (editingCustomIconPreviews[editingIconPickerSlot] == null) return;
            Bitmap bitmap = ImageUtils.getBitmapFromUri(this, data.getData(), 256);
            if (bitmap == null) {
                AppUtils.showToast(this, R.string.unable_to_load_image);
                return;
            }

            Bitmap normalizedBitmap = normalizeIconBitmap(bitmap, (int)UnitUtils.dpToPx(64));
            editingCustomIconData[editingIconPickerSlot] = encodeBitmapToBase64(normalizedBitmap);
            editingSelectedIconIds[editingIconPickerSlot] = 0;
            clearBuiltinIconSelection(editingIconPickerSlot);
            updateCustomIconPreview(editingIconPickerSlot, normalizedBitmap);
        }
        else if (requestCode == IMPORT_ICON_PACK_REQUEST_CODE && resultCode == Activity.RESULT_OK && data != null && data.getData() != null) {
            try {
                IconPackManager.StoredIconPack pack = iconPackManager.importPack(data.getData());
                if (pack != null) {
                    AppUtils.showToast(this, getString(R.string.icon_pack_imported, pack.name));
                    refreshEditingIconList();
                }
                else AppUtils.showToast(this, R.string.unable_to_import_icon_pack);
            }
            catch (Exception e) {
                e.printStackTrace();
                AppUtils.showToast(this, getString(R.string.unable_to_import_icon_pack_with_reason, getImportErrorMessage(e)));
            }
        }
        else if (requestCode == EXPORT_ICON_PACK_REQUEST_CODE && resultCode == Activity.RESULT_OK && data != null && data.getData() != null) {
            try {
                IconPackManager.StoredIconPack pack = iconPackManager.getActivePack();
                if (pack != null && pendingExportPackId != null && pendingExportPackId.equals(pack.id) && iconPackManager.exportPack(pack, data.getData())) {
                    AppUtils.showToast(this, getString(R.string.icon_pack_exported, pack.name));
                }
                else AppUtils.showToast(this, R.string.unable_to_export_icon_pack);
            }
            catch (Exception e) {
                AppUtils.showToast(this, R.string.unable_to_export_icon_pack);
            }
            pendingExportPackId = null;
        }
        else if (requestCode == CREATE_ICON_PACK_REQUEST_CODE) {
            try {
                if (resultCode != Activity.RESULT_OK || data == null) return;

                ArrayList<Uri> selectedUris = getSelectedImageUris(data);
                ArrayList<IconPackManager.SourceIcon> sourceIcons = loadIconPackSourceIcons(selectedUris);
                if (sourceIcons.isEmpty()) {
                    AppUtils.showToast(this, R.string.unable_to_load_image);
                    return;
                }

                IconPackManager.StoredIconPack pack = iconPackManager.createPack(pendingCreatePackName, "", sourceIcons);
                if (pack != null) {
                    AppUtils.showToast(this, getString(R.string.icon_pack_created, pack.name));
                    refreshEditingIconList();
                }
                else AppUtils.showToast(this, R.string.unable_to_create_icon_pack);
            }
            catch (Exception e) {
                AppUtils.showToast(this, R.string.unable_to_create_icon_pack);
            }
            finally {
                pendingCreatePackName = null;
            }
        }
    }

    private void moveToolbox(float x, float y) {
        final int padding = (int)UnitUtils.dpToPx(8);
        ViewGroup parent = (ViewGroup)toolbox.getParent();
        int width = toolbox.getWidth();
        int height = toolbox.getHeight();
        int parentWidth = parent.getWidth();
        int parentHeight = parent.getHeight();
        x = Mathf.clamp(x, padding, parentWidth - padding - width);
        y = Mathf.clamp(y, padding, parentHeight - padding - height);
        toolbox.setX(x);
        toolbox.setY(y);
    }

    @Override
    public void onClick(View v) {
        switch (v.getId()) {
            case R.id.BTAddElement:
                if (!inputControlsView.addElement()) {
                    AppUtils.showToast(this, R.string.no_profile_selected);
                }
                break;
            case R.id.BTRemoveElement:
                if (!inputControlsView.removeElement()) {
                    AppUtils.showToast(this, R.string.no_control_element_selected);
                }
                break;
            case R.id.BTElementSettings:
                ControlElement selectedElement = inputControlsView.getSelectedElement();
                if (selectedElement != null) {
                    showControlElementSettings(v);
                }
                else AppUtils.showToast(this, R.string.no_control_element_selected);
                break;
            case R.id.BTIconPackManager:
                startActivity(new Intent(this, IconPackManagerActivity.class));
                break;
        }
    }

    private void showControlElementSettings(View anchorView) {
        final ControlElement element = inputControlsView.getSelectedElement();
        final View view = LayoutInflater.from(this).inflate(R.layout.control_element_settings, null);

        final Runnable updateLayout = () -> {
            ControlElement.Type type = element.getType();
            view.findViewById(R.id.LLShape).setVisibility(View.GONE);
            view.findViewById(R.id.CBToggleSwitch).setVisibility(View.GONE);
            view.findViewById(R.id.CBMouseMoveMode).setVisibility(View.GONE);
            view.findViewById(R.id.LLCustomTextIcon).setVisibility(View.GONE);
            view.findViewById(R.id.LLRangeOptions).setVisibility(View.GONE);
            view.findViewById(R.id.LLMIDIKeyOptions).setVisibility(View.GONE);
            view.findViewById(R.id.LLTrackpadOptions).setVisibility(View.GONE);
            view.findViewById(R.id.LLRadialMenuOptions).setVisibility(View.GONE);

            switch (type) {
                case BUTTON:
                    view.findViewById(R.id.LLShape).setVisibility(View.VISIBLE);
                    view.findViewById(R.id.CBToggleSwitch).setVisibility(View.VISIBLE);
                    view.findViewById(R.id.CBMouseMoveMode).setVisibility(View.VISIBLE);
                    view.findViewById(R.id.LLCustomTextIcon).setVisibility(View.VISIBLE);
                    break;
                case RANGE_BUTTON:
                    view.findViewById(R.id.LLRangeOptions).setVisibility(View.VISIBLE);
                    break;
                case MIDI_KEY:
                    view.findViewById(R.id.LLMIDIKeyOptions).setVisibility(View.VISIBLE);
                    break;
                case TRACKPAD:
                    view.findViewById(R.id.LLTrackpadOptions).setVisibility(View.VISIBLE);
                    ((Spinner)view.findViewById(R.id.STrackpadPressBinding)).setSelection(getTrackpadPressBindingIndex(element.getTrackpadPressBinding()), false);
                    ((SeekBar)view.findViewById(R.id.SBTrackpadPressOffsetX)).setValue(element.getTrackpadPressOffsetX());
                    ((SeekBar)view.findViewById(R.id.SBTrackpadPressOffsetY)).setValue(element.getTrackpadPressOffsetY());
                    break;
                case RADIAL_MENU:
                    ((NumberPicker)view.findViewById(R.id.NPBindings)).setValue(element.getBindingCount());
                    view.findViewById(R.id.LLRadialMenuOptions).setVisibility(View.VISIBLE);
                    break;
            }

            loadBindingSpinners(element, view);
            updateSwapIconSection(view, element);
        };

        loadTypeSpinner(element, view.findViewById(R.id.SType), updateLayout);
        loadShapeSpinner(element, view.findViewById(R.id.SShape));
        loadRangeSpinner(element, view.findViewById(R.id.SRange));
        loadNoteSpinner(element, view.findViewById(R.id.SNote));
        loadTrackpadPressBindingSpinner(element, view.findViewById(R.id.STrackpadPressBinding));

        SeekBar sbTrackpadPressOffsetX = view.findViewById(R.id.SBTrackpadPressOffsetX);
        sbTrackpadPressOffsetX.setValue(element.getTrackpadPressOffsetX());
        sbTrackpadPressOffsetX.setOnValueChangeListener((seekBar, value) -> {
            element.setTrackpadPressOffsetX(Math.round(value));
            profile.save();
        });

        SeekBar sbTrackpadPressOffsetY = view.findViewById(R.id.SBTrackpadPressOffsetY);
        sbTrackpadPressOffsetY.setValue(element.getTrackpadPressOffsetY());
        sbTrackpadPressOffsetY.setOnValueChangeListener((seekBar, value) -> {
            element.setTrackpadPressOffsetY(Math.round(value));
            profile.save();
        });

        RadioGroup rgOrientation = view.findViewById(R.id.RGOrientation);
        rgOrientation.check(element.getOrientation() == 1 ? R.id.RBVertical : R.id.RBHorizontal);
        rgOrientation.setOnCheckedChangeListener((group, checkedId) -> {
            element.setOrientation((byte)(checkedId == R.id.RBVertical ? 1 : 0));
            profile.save();
            inputControlsView.invalidate();
        });

        NumberPicker npColumns = view.findViewById(R.id.NPColumns);
        npColumns.setValue(element.getBindingCount());
        npColumns.setOnValueChangeListener((numberPicker, value) -> {
            element.setBindingCount(value);
            profile.save();
            inputControlsView.invalidate();
        });

        NumberPicker npBindings = view.findViewById(R.id.NPBindings);
        npBindings.setValue(element.getBindingCount());
        npBindings.setOnValueChangeListener((numberPicker, value) -> {
            element.setBindingCount(value);
            loadBindingSpinners(element, view);
            profile.save();
            inputControlsView.invalidate();
        });

        SeekBar sbScale = view.findViewById(R.id.SBScale);
        sbScale.setOnValueChangeListener((seekBar, value) -> {
            element.setScale(value / 100.0f);
            profile.save();
            inputControlsView.invalidate();
        });
        sbScale.setValue(element.getScale() * 100);

        SeekBar sbOpacity = view.findViewById(R.id.SBOpacity);
        sbOpacity.setOnValueChangeListener((seekBar, value) -> {
            element.setOpacity(value / 100.0f);
            profile.save();
            inputControlsView.invalidate();
        });
        sbOpacity.setValue(element.getOpacity() * 100);

        CheckBox cbToggleSwitch = view.findViewById(R.id.CBToggleSwitch);
        cbToggleSwitch.setChecked(element.isToggleSwitch());
        cbToggleSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            element.setToggleSwitch(isChecked);
            profile.save();
        });

        CheckBox cbMouseMoveMode = view.findViewById(R.id.CBMouseMoveMode);
        cbMouseMoveMode.setChecked(element.isMouseMoveMode());
        cbMouseMoveMode.setOnCheckedChangeListener((buttonView, isChecked) -> {
            element.setMouseMoveMode(isChecked);
            profile.save();
        });

        final EditText etCustomText = view.findViewById(R.id.ETCustomText);
        etCustomText.setText(element.getText());
        final SeekBar sbIconScale = view.findViewById(R.id.SBIconScale);
        sbIconScale.setValue(element.getIconScale() * 100);
        sbIconScale.setOnValueChangeListener((seekBar, value) -> {
            element.setIconScale(value / 100.0f);
            profile.save();
            inputControlsView.invalidate();
        });

        final SeekBar sbIconOpacity = view.findViewById(R.id.SBIconOpacity);
        sbIconOpacity.setValue(element.getIconOpacity() * 100);
        sbIconOpacity.setOnValueChangeListener((seekBar, value) -> {
            element.setIconOpacity(value / 100.0f);
            profile.save();
            inputControlsView.invalidate();
        });

        final ColorPickerView cpvPressedColor = view.findViewById(R.id.CPVPressedColor);
        cpvPressedColor.setPalette(0x000000, 0xffffff, 0xd32f2f, 0xff6f00, 0xffea00, 0x2e7d32, 0x00838f, 0x1565c0);
        cpvPressedColor.setColor(element.getPressedColor());

        editingElement = element;
        configureIconPicker(
            view,
            ICON_SLOT_PRIMARY,
            R.id.LLIconList,
            R.id.IVCustomIconPreview,
            R.id.BTBrowseCustomIcon,
            R.id.BTClearCustomIcon,
            element.hasCustomIcon() ? 0 : element.getIconId(),
            element.getCustomIconData(),
            element.getCustomIcon()
        );
        configureIconPicker(
            view,
            ICON_SLOT_SECONDARY,
            R.id.LLSecondaryIconList,
            R.id.IVSecondaryCustomIconPreview,
            R.id.BTBrowseSecondaryCustomIcon,
            R.id.BTClearSecondaryCustomIcon,
            element.hasSecondaryCustomIcon() ? 0 : element.getSecondaryIconId(),
            element.getSecondaryCustomIconData(),
            element.getSecondaryCustomIcon()
        );

        updateLayout.run();

        elementSettingsPopup = AppUtils.showPopupWindow(anchorView, view, 340, 0);
        elementSettingsPopup.setOnDismissListener(() -> {
            if (element.getType() == ControlElement.Type.BUTTON) {
                String text = etCustomText.getText().toString().trim();
                element.setText(text);
                element.setCustomIconData(editingCustomIconData[ICON_SLOT_PRIMARY]);
                element.setIconId(editingCustomIconData[ICON_SLOT_PRIMARY].isEmpty() ? editingSelectedIconIds[ICON_SLOT_PRIMARY] : 0);
                element.setSecondaryCustomIconData(editingCustomIconData[ICON_SLOT_SECONDARY]);
                element.setSecondaryIconId(editingCustomIconData[ICON_SLOT_SECONDARY].isEmpty() ? editingSelectedIconIds[ICON_SLOT_SECONDARY] : 0);
                element.setPressedColor(cpvPressedColor.getColor());
            }
            profile.save();
            inputControlsView.invalidate();
            clearEditingState();
        });
    }

    private void loadTypeSpinner(final ControlElement element, Spinner spinner, final Runnable callback) {
        spinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, ControlElement.Type.names()));
        spinner.setSelection(element.getType().ordinal(), false);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                ControlElement.Type newType = ControlElement.Type.values()[position];
                if (newType == element.getType()) return;
                element.setType(newType);
                profile.save();
                callback.run();
                inputControlsView.invalidate();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    private void loadShapeSpinner(final ControlElement element, Spinner spinner) {
        spinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, ControlElement.Shape.names()));
        spinner.setSelection(element.getShape().ordinal(), false);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                element.setShape(ControlElement.Shape.values()[position]);
                profile.save();
                inputControlsView.invalidate();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    private void loadBindingSpinners(ControlElement element, View view) {
        LinearLayout container = view.findViewById(R.id.LLBindings);
        container.removeAllViews();

        ControlElement.Type type = element.getType();
        if (type == ControlElement.Type.BUTTON) {
            byte first = element.getFirstBindingIndex();
            for (byte i = 0, count = 0; i < element.getBindingCount(); i++) {
                if (i <= first || element.getBindingAt(i) != Binding.NONE) {
                    loadBindingSpinner(element, view, container, i, count++ == 0 ? R.string.binding : 0);
                }
            }
        }
        else if (type == ControlElement.Type.D_PAD || type == ControlElement.Type.STICK || type == ControlElement.Type.TRACKPAD) {
            loadBindingSpinner(element, view, container, 0, R.string.binding_up);
            loadBindingSpinner(element, view, container, 1, R.string.binding_right);
            loadBindingSpinner(element, view, container, 2, R.string.binding_down);
            loadBindingSpinner(element, view, container, 3, R.string.binding_left);
        }
        else if (type == ControlElement.Type.RADIAL_MENU) {
            for (byte i = 0; i < element.getBindingCount(); i++) loadBindingSpinner(element, view, container, i, 0);
        }
    }

    private void loadBindingSpinner(final ControlElement element, final View settingsView, final LinearLayout container, final int index, int titleResId) {
        View view = LayoutInflater.from(this).inflate(R.layout.binding_field, container, false);

        LinearLayout titleBar = view.findViewById(R.id.LLTitleBar);
        if (titleResId > 0) {
            titleBar.setVisibility(View.VISIBLE);
            ((TextView)view.findViewById(R.id.TVTitle)).setText(titleResId);
        }
        else titleBar.setVisibility(View.GONE);

        final Spinner sBindingType = view.findViewById(R.id.SBindingType);
        final Spinner sBinding = view.findViewById(R.id.SBinding);

        ControlElement.Type type = element.getType();
        if (type == ControlElement.Type.BUTTON || type == ControlElement.Type.RADIAL_MENU) {
            ImageButton addButton = view.findViewById(R.id.BTAdd);
            addButton.setVisibility(View.VISIBLE);
            addButton.setOnClickListener((v) -> {
                int nextIndex = container.getChildCount();
                if (nextIndex < element.getBindingCount()) loadBindingSpinner(element, settingsView, container, nextIndex, 0);
            });
        }

        Runnable update = () -> {
            String[] bindingEntries = null;
            switch (sBindingType.getSelectedItemPosition()) {
                case 0:
                    bindingEntries = Binding.keyboardBindingLabels();
                    break;
                case 1:
                    bindingEntries = Binding.mouseBindingLabels();
                    break;
                case 2:
                    bindingEntries = Binding.gamepadBindingLabels();
                    break;
            }

            sBinding.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, bindingEntries));
            AppUtils.setSpinnerSelectionFromValue(sBinding, element.getBindingAt(index).toString());
        };

        sBindingType.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                update.run();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        Binding selectedBinding = element.getBindingAt(index);
        if (selectedBinding.isKeyboard()) {
            sBindingType.setSelection(0, false);
        }
        else if (selectedBinding.isMouse()) {
            sBindingType.setSelection(1, false);
        }
        else if (selectedBinding.isGamepad()) {
            sBindingType.setSelection(2, false);
        }

        sBinding.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                Binding binding = Binding.NONE;
                switch (sBindingType.getSelectedItemPosition()) {
                    case 0:
                        binding = Binding.keyboardBindingValues()[position];
                        break;
                    case 1:
                        binding = Binding.mouseBindingValues()[position];
                        break;
                    case 2:
                        binding = Binding.gamepadBindingValues()[position];
                        break;
                }

                if (binding != element.getBindingAt(index)) {
                    element.setBindingAt(index, binding);
                    profile.save();
                    inputControlsView.invalidate();
                    updateSwapIconSection(settingsView, element);
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        update.run();
        container.addView(view);
    }

    private void loadRangeSpinner(final ControlElement element, Spinner spinner) {
        spinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, ControlElement.Range.names()));
        spinner.setSelection(element.getRange().ordinal(), false);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                element.setRange(ControlElement.Range.values()[position]);
                profile.save();
                inputControlsView.invalidate();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    private void loadTrackpadPressBindingSpinner(final ControlElement element, Spinner spinner) {
        String[] labels = new String[TRACKPAD_PRESS_BINDINGS.length];
        labels[0] = getString(R.string.none);
        for (int i = 1; i < TRACKPAD_PRESS_BINDINGS.length; i++) labels[i] = TRACKPAD_PRESS_BINDINGS[i].toString();

        spinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, labels));
        spinner.setSelection(getTrackpadPressBindingIndex(element.getTrackpadPressBinding()), false);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                Binding binding = TRACKPAD_PRESS_BINDINGS[position];
                if (binding != element.getTrackpadPressBinding()) {
                    element.setTrackpadPressBinding(binding);
                    profile.save();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    private static int getTrackpadPressBindingIndex(Binding binding) {
        for (int i = 0; i < TRACKPAD_PRESS_BINDINGS.length; i++) {
            if (TRACKPAD_PRESS_BINDINGS[i] == binding) return i;
        }
        return 0;
    }

    private void loadNoteSpinner(final ControlElement element, Spinner spinner) {
        String[] notes = MIDIHandler.getNotes();
        spinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, notes));
        AppUtils.setSpinnerSelectionFromValue(spinner, element.getText());
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                element.setText(notes[position]);
                profile.save();
                inputControlsView.invalidate();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    private void updateSwapIconSection(View settingsView, ControlElement element) {
        View section = settingsView.findViewById(R.id.LLSecondaryIconSection);
        boolean visible = element.getType() == ControlElement.Type.BUTTON && element.hasBinding(Binding.MOUSE_SWAPL_R_BUTTONS);
        section.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    private void configureIconPicker(
        View settingsView,
        int slot,
        int iconListId,
        int previewId,
        int browseButtonId,
        int clearButtonId,
        int selectedIconId,
        String selectedCustomIconData,
        Bitmap previewBitmap
    ) {
        editingIconLists[slot] = settingsView.findViewById(iconListId);
        editingCustomIconPreviews[slot] = settingsView.findViewById(previewId);
        editingSelectedIconIds[slot] = (byte)selectedIconId;
        editingCustomIconData[slot] = selectedCustomIconData != null ? selectedCustomIconData : "";

        updateCustomIconPreview(slot, previewBitmap);
        settingsView.findViewById(browseButtonId).setOnClickListener((v) -> openCustomIconPicker(slot));
        settingsView.findViewById(clearButtonId).setOnClickListener((v) -> {
            editingCustomIconData[slot] = "";
            editingSelectedIconIds[slot] = 0;
            clearBuiltinIconSelection(slot);
            updateCustomIconPreview(slot, null);
        });
        loadIcons(editingIconLists[slot], slot, editingSelectedIconIds[slot], editingCustomIconData[slot]);
    }

    private void loadIcons(final LinearLayout parent, final int slot, byte selectedId, String selectedCustomIconData) {
        parent.removeAllViews();
        byte[] iconIds = new byte[0];
        try {
            String[] filenames = getAssets().list("inputcontrols/icons/");
            iconIds = new byte[filenames.length];
            for (int i = 0; i < filenames.length; i++) {
                iconIds[i] = Byte.parseByte(FileUtils.getBasename(filenames[i]));
            }
        }
        catch (IOException e) {}

        Arrays.sort(iconIds);

        int size = (int)UnitUtils.dpToPx(40);
        int margin = (int)UnitUtils.dpToPx(2);
        int padding = (int)UnitUtils.dpToPx(4);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(size, size);
        params.setMargins(margin, 0, margin, 0);

        for (final byte id : iconIds) {
            ImageView imageView = new ImageView(this);
            imageView.setLayoutParams(params);
            imageView.setPadding(padding, padding, padding, padding);
            imageView.setBackgroundResource(R.drawable.icon_background);
            imageView.setTag(id);
            imageView.setSelected(id == selectedId);
            imageView.setOnClickListener((v) -> {
                for (int i = 0; i < parent.getChildCount(); i++) parent.getChildAt(i).setSelected(false);
                imageView.setSelected(true);
                editingSelectedIconIds[slot] = id;
                editingCustomIconData[slot] = "";
                updateCustomIconPreview(slot, null);
            });

            try (InputStream is = getAssets().open("inputcontrols/icons/"+id+".png")) {
                imageView.setImageBitmap(BitmapFactory.decodeStream(is));
            }
            catch (IOException e) {}

            parent.addView(imageView);
        }

        for (IconPackManager.StoredIconPack activePack : iconPackManager.getActivePacks()) {
            for (final IconPackManager.PackIcon packIcon : activePack.icons) {
                final byte[] iconData = packIcon.readBytes();
                if (iconData == null || iconData.length == 0) continue;

                final String base64IconData = Base64.encodeToString(iconData, Base64.NO_WRAP);
                ImageView imageView = new ImageView(this);
                imageView.setLayoutParams(params);
                imageView.setPadding(padding, padding, padding, padding);
                imageView.setBackgroundResource(R.drawable.icon_background);
                imageView.setSelected(base64IconData.equals(selectedCustomIconData));
                imageView.setOnClickListener((v) -> {
                    for (int i = 0; i < parent.getChildCount(); i++) parent.getChildAt(i).setSelected(false);
                    imageView.setSelected(true);
                    editingSelectedIconIds[slot] = 0;
                    editingCustomIconData[slot] = base64IconData;
                    Bitmap bitmap = BitmapFactory.decodeByteArray(iconData, 0, iconData.length);
                    updateCustomIconPreview(slot, bitmap);
                });
                imageView.setImageBitmap(BitmapFactory.decodeByteArray(iconData, 0, iconData.length));
                parent.addView(imageView);
            }
        }
    }

    private void openCustomIconPicker(int slot) {
        editingIconPickerSlot = slot;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        startActivityForResult(intent, OPEN_CUSTOM_ICON_REQUEST_CODE);
    }

    private void clearBuiltinIconSelection(int slot) {
        LinearLayout editingIconList = editingIconLists[slot];
        if (editingIconList == null) return;
        for (int i = 0; i < editingIconList.getChildCount(); i++) {
            editingIconList.getChildAt(i).setSelected(false);
        }
    }

    private void updateCustomIconPreview(int slot, Bitmap bitmap) {
        ImageView editingCustomIconPreview = editingCustomIconPreviews[slot];
        if (editingCustomIconPreview == null) return;

        if (bitmap != null) {
            editingCustomIconPreview.setImageBitmap(bitmap);
            editingCustomIconPreview.setSelected(true);
        }
        else {
            editingCustomIconPreview.setImageResource(R.drawable.icon_image_picker);
            editingCustomIconPreview.setSelected(false);
        }
    }

    private void clearEditingState() {
        elementSettingsPopup = null;
        editingElement = null;
        for (int i = 0; i < editingIconLists.length; i++) {
            editingIconLists[i] = null;
            editingCustomIconPreviews[i] = null;
            editingSelectedIconIds[i] = 0;
            editingCustomIconData[i] = "";
        }
        editingIconPickerSlot = ICON_SLOT_PRIMARY;
    }

    private void refreshEditingIconList() {
        for (int i = 0; i < editingIconLists.length; i++) {
            if (editingIconLists[i] != null) loadIcons(editingIconLists[i], i, editingSelectedIconIds[i], editingCustomIconData[i]);
        }
    }

    private void showIconPackManager(View anchor) {
        PopupMenu popupMenu = new PopupMenu(this, anchor);
        popupMenu.inflate(R.menu.icon_pack_manager_popup_menu);
        popupMenu.setOnMenuItemClickListener((menuItem) -> {
            int itemId = menuItem.getItemId();
            if (itemId == R.id.menu_item_import_icon_pack) {
                openIconPackFile();
            }
            else if (itemId == R.id.menu_item_create_icon_pack) {
                promptCreateIconPack();
            }
            else if (itemId == R.id.menu_item_select_icon_pack) {
                selectActiveIconPack();
            }
            else if (itemId == R.id.menu_item_export_icon_pack) {
                exportActiveIconPack();
            }
            else if (itemId == R.id.menu_item_remove_icon_pack) {
                removeActiveIconPack();
            }
            return true;
        });
        popupMenu.show();
    }

    private void promptCreateIconPack() {
        String profileName = profile != null && profile.getName() != null && !profile.getName().trim().isEmpty()
            ? profile.getName().trim()
            : getString(R.string.untitled);

        ContentDialog.prompt(this, R.string.create_icon_pack, getString(R.string.icon_pack_default_name, profileName), this::openCreateIconPackFiles);
    }

    private void openCreateIconPackFiles(String packName) {
        pendingCreatePackName = packName;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(intent, CREATE_ICON_PACK_REQUEST_CODE);
    }

    private ArrayList<Uri> getSelectedImageUris(Intent data) {
        ArrayList<Uri> uris = new ArrayList<>();
        if (data.getClipData() != null) {
            for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                Uri uri = data.getClipData().getItemAt(i).getUri();
                if (uri != null) uris.add(uri);
            }
        }
        else if (data.getData() != null) uris.add(data.getData());
        return uris;
    }

    private ArrayList<IconPackManager.SourceIcon> loadIconPackSourceIcons(ArrayList<Uri> uris) {
        ArrayList<IconPackManager.SourceIcon> iconBytesList = new ArrayList<>();
        for (Uri uri : uris) {
            Bitmap bitmap = ImageUtils.getBitmapFromUri(this, uri, 256);
            if (bitmap == null) continue;

            Bitmap normalizedBitmap = normalizeIconBitmap(bitmap, 256);
            byte[] bytes = encodeBitmapToBytes(normalizedBitmap);
            if (bytes.length > 0) iconBytesList.add(new IconPackManager.SourceIcon("", uri.toString(), 0, bytes));
        }
        return iconBytesList;
    }

    private void openIconPackFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, IMPORT_ICON_PACK_REQUEST_CODE);
    }

    private void selectActiveIconPack() {
        final java.util.ArrayList<IconPackManager.StoredIconPack> packs = iconPackManager.getPacks();
        String[] items = new String[packs.size() + 1];
        items[0] = getString(R.string.icon_pack_none);
        for (int i = 0; i < packs.size(); i++) items[i + 1] = packs.get(i).name;

        ContentDialog.showSelectionList(this, R.string.select_icon_pack, items, true, (positions) -> {
            LinkedHashSet<String> selectedPackIds = new LinkedHashSet<>();
            boolean disableAll = false;

            for (Integer position : positions) {
                if (position == null) continue;
                if (position == 0) {
                    disableAll = true;
                    break;
                }

                int packIndex = position - 1;
                if (packIndex >= 0 && packIndex < packs.size()) selectedPackIds.add(packs.get(packIndex).id);
            }

            iconPackManager.setActivePackIds(disableAll ? new LinkedHashSet<>() : selectedPackIds);
            if (disableAll || selectedPackIds.isEmpty()) AppUtils.showToast(this, R.string.icon_pack_disabled);
            else AppUtils.showToast(this, getString(R.string.icon_packs_selected, selectedPackIds.size()));
            refreshEditingIconList();
        });
    }

    private void exportActiveIconPack() {
        IconPackManager.StoredIconPack pack = iconPackManager.getActivePack();
        if (pack == null) {
            AppUtils.showToast(this, R.string.no_icon_pack_selected);
            return;
        }

        pendingExportPackId = pack.id;
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_TITLE, FileUtils.getName(pack.name)+".ipk");
        startActivityForResult(intent, EXPORT_ICON_PACK_REQUEST_CODE);
    }

    private void removeActiveIconPack() {
        IconPackManager.StoredIconPack pack = iconPackManager.getActivePack();
        if (pack == null) {
            AppUtils.showToast(this, R.string.no_icon_pack_selected);
            return;
        }

        ContentDialog.confirm(this, R.string.do_you_want_to_remove_this_icon_pack, () -> {
            if (iconPackManager.removePack(pack.id)) {
                AppUtils.showToast(this, getString(R.string.icon_pack_removed, pack.name));
                refreshEditingIconList();
            }
            else AppUtils.showToast(this, R.string.unable_to_remove_icon_pack);
        });
    }

    private static Bitmap normalizeIconBitmap(Bitmap bitmap, int size) {
        Bitmap normalizedBitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(normalizedBitmap);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        float scale = Math.min((float)size / bitmap.getWidth(), (float)size / bitmap.getHeight());
        float width = bitmap.getWidth() * scale;
        float height = bitmap.getHeight() * scale;
        float left = (size - width) * 0.5f;
        float top = (size - height) * 0.5f;
        canvas.drawBitmap(bitmap, null, new RectF(left, top, left + width, top + height), paint);
        return normalizedBitmap;
    }

    private static byte[] encodeBitmapToBytes(Bitmap bitmap) {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream);
        return outputStream.toByteArray();
    }

    private static String encodeBitmapToBase64(Bitmap bitmap) {
        return Base64.encodeToString(encodeBitmapToBytes(bitmap), Base64.NO_WRAP);
    }

    private static String getImportErrorMessage(Exception e) {
        String message = e.getMessage();
        if (message == null || message.trim().isEmpty()) return e.getClass().getSimpleName();
        return message;
    }
}
