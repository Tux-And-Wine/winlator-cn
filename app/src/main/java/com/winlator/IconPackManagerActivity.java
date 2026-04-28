package com.winlator;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.winlator.contentdialog.ContentDialog;
import com.winlator.core.AppUtils;
import com.winlator.core.LocaleHelper;
import com.winlator.core.StringUtils;
import com.winlator.inputcontrols.IconPackManager;
import com.winlator.inputcontrols.IconPackUtils;

import java.io.IOException;
import java.util.ArrayList;

public class IconPackManagerActivity extends AppCompatActivity {
    private static final int IMPORT_ICON_PACK_REQUEST_CODE = 3001;
    private static final int CREATE_ICON_PACK_IMAGES_REQUEST_CODE = 3002;
    private static final int ICON_TARGET_SIZE = 256;

    private IconPackManager iconPackManager;
    private TextView summaryTextView;
    private TextView emptyTextView;
    private RecyclerView recyclerView;
    private final ArrayList<IconPackManager.StoredIconPack> packs = new ArrayList<>();
    private PacksAdapter adapter;

    private ContentDialog createDialog;
    private EditText createNameEditText;
    private EditText createAuthorEditText;
    private TextView selectedIconsTextView;
    private final ArrayList<IconPackManager.SourceIcon> pendingCreateIcons = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        AppUtils.setActivityTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.icon_pack_manager_activity);

        iconPackManager = new IconPackManager(this);
        summaryTextView = findViewById(R.id.TVSummary);
        emptyTextView = findViewById(R.id.TVEmptyText);
        recyclerView = findViewById(R.id.RecyclerView);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(adapter = new PacksAdapter());

        ((Button)findViewById(R.id.BTCreate)).setOnClickListener((v) -> showCreateDialog());
        ((Button)findViewById(R.id.BTInstall)).setOnClickListener((v) -> openIconPackFile());
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshPacks();
    }

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleHelper.setSystemLocale(newBase));
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (resultCode != Activity.RESULT_OK || data == null) return;

        if (requestCode == IMPORT_ICON_PACK_REQUEST_CODE && data.getData() != null) {
            try {
                IconPackManager.StoredIconPack pack = iconPackManager.importPack(data.getData());
                if (pack != null) {
                    AppUtils.showToast(this, getString(R.string.icon_pack_imported, pack.name));
                    refreshPacks();
                }
                else AppUtils.showToast(this, R.string.unable_to_import_icon_pack);
            }
            catch (Exception e) {
                AppUtils.showToast(this, getString(R.string.unable_to_import_icon_pack_with_reason, getImportErrorMessage(e)));
            }
        }
        else if (requestCode == CREATE_ICON_PACK_IMAGES_REQUEST_CODE) {
            pendingCreateIcons.clear();
            pendingCreateIcons.addAll(IconPackUtils.loadSourceIcons(this, IconPackUtils.getSelectedUris(data), ICON_TARGET_SIZE));
            updateCreateDialogSelectedState();
        }
    }

    private void refreshPacks() {
        packs.clear();
        packs.addAll(iconPackManager.getPacks());
        summaryTextView.setText(getString(R.string.icon_pack_found_count, packs.size()));
        emptyTextView.setVisibility(packs.isEmpty() ? View.VISIBLE : View.GONE);
        recyclerView.setVisibility(packs.isEmpty() ? View.GONE : View.VISIBLE);
        adapter.notifyDataSetChanged();
    }

    private void openIconPackFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, IMPORT_ICON_PACK_REQUEST_CODE);
    }

    private void showCreateDialog() {
        createDialog = new ContentDialog(this, R.layout.create_icon_pack_dialog);
        createDialog.findViewById(R.id.LLBottomBar).setVisibility(View.GONE);
        createDialog.setCancelable(true);

        createNameEditText = createDialog.findViewById(R.id.ETName);
        createAuthorEditText = createDialog.findViewById(R.id.ETAuthor);
        selectedIconsTextView = createDialog.findViewById(R.id.TVSelectedIcons);
        pendingCreateIcons.clear();

        createDialog.findViewById(R.id.BTSelectIcons).setOnClickListener((v) -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("image/*");
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            startActivityForResult(intent, CREATE_ICON_PACK_IMAGES_REQUEST_CODE);
        });

        createDialog.findViewById(R.id.BTCreate).setOnClickListener((v) -> createIconPack());
        updateCreateDialogSelectedState();
        createDialog.show();
    }

    private void updateCreateDialogSelectedState() {
        if (selectedIconsTextView == null) return;

        if (pendingCreateIcons.isEmpty()) {
            selectedIconsTextView.setText(R.string.no_icons_selected);
        }
        else selectedIconsTextView.setText(getString(R.string.selected_icon_count, pendingCreateIcons.size()));
    }

    private void createIconPack() {
        if (createNameEditText == null || createAuthorEditText == null) return;

        String name = createNameEditText.getText().toString().trim();
        String author = createAuthorEditText.getText().toString().trim();

        if (name.isEmpty()) {
            AppUtils.showToast(this, R.string.icon_pack_name_required);
            return;
        }
        if (pendingCreateIcons.isEmpty()) {
            AppUtils.showToast(this, R.string.icon_pack_icons_required);
            return;
        }

        try {
            IconPackManager.StoredIconPack pack = iconPackManager.createPack(name, author, pendingCreateIcons);
            if (pack != null) {
                AppUtils.showToast(this, getString(R.string.icon_pack_created, pack.name));
                if (createDialog != null) createDialog.dismiss();
                refreshPacks();
            }
            else AppUtils.showToast(this, R.string.unable_to_create_icon_pack);
        }
        catch (Exception e) {
            AppUtils.showToast(this, R.string.unable_to_create_icon_pack);
        }
    }

    private void openPackDetails(IconPackManager.StoredIconPack pack) {
        Intent intent = new Intent(this, IconPackDetailsActivity.class);
        intent.putExtra("pack_id", pack.id);
        startActivity(intent);
    }

    private void togglePackEnabled(IconPackManager.StoredIconPack pack) {
        String activePackId = iconPackManager.getActivePackId();
        boolean isActive = pack.id.equals(activePackId);
        iconPackManager.setActivePackId(isActive ? null : pack.id);
        AppUtils.showToast(this, isActive ? getString(R.string.icon_pack_disabled) : getString(R.string.icon_pack_selected, pack.name));
        refreshPacks();
    }

    private void confirmRemovePack(IconPackManager.StoredIconPack pack) {
        ContentDialog.confirm(this, R.string.do_you_want_to_remove_this_icon_pack, () -> {
            if (iconPackManager.removePack(pack.id)) {
                AppUtils.showToast(this, getString(R.string.icon_pack_removed, pack.name));
                refreshPacks();
            }
            else AppUtils.showToast(this, R.string.unable_to_remove_icon_pack);
        });
    }

    private class PacksAdapter extends RecyclerView.Adapter<PacksAdapter.ViewHolder> {
        private class ViewHolder extends RecyclerView.ViewHolder {
            private final View rootView;
            private final ImageView previewImageView;
            private final TextView nameTextView;
            private final TextView metaTextView;
            private final Button actionButton;
            private final ImageView removeButton;

            private ViewHolder(View view) {
                super(view);
                rootView = view;
                previewImageView = view.findViewById(R.id.IVPreview);
                nameTextView = view.findViewById(R.id.TVName);
                metaTextView = view.findViewById(R.id.TVMeta);
                actionButton = view.findViewById(R.id.BTAction);
                removeButton = view.findViewById(R.id.BTRemove);
            }
        }

        @Override
        public ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            return new ViewHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.icon_pack_list_item, parent, false));
        }

        @Override
        public void onBindViewHolder(ViewHolder holder, int position) {
            IconPackManager.StoredIconPack pack = packs.get(position);
            String activePackId = iconPackManager.getActivePackId();
            boolean isActive = pack.id.equals(activePackId);

            holder.nameTextView.setText(pack.name);
            holder.metaTextView.setText(buildPackMeta(pack));
            bindPreview(holder.previewImageView, pack);
            holder.actionButton.setText(isActive ? R.string.disable_icon_pack : R.string.enable_icon_pack);
            holder.actionButton.setOnClickListener((v) -> togglePackEnabled(pack));
            holder.removeButton.setOnClickListener((v) -> confirmRemovePack(pack));
            holder.rootView.setOnClickListener((v) -> openPackDetails(pack));
        }

        @Override
        public int getItemCount() {
            return packs.size();
        }
    }

    private void bindPreview(ImageView imageView, IconPackManager.StoredIconPack pack) {
        byte[] bytes = null;
        if (!pack.icons.isEmpty()) bytes = pack.icons.get(0).readBytes();
        else if (pack.customIcon != null) bytes = pack.customIcon.readBytes();

        if (bytes != null && bytes.length > 0) {
            imageView.clearColorFilter();
            imageView.setImageBitmap(BitmapFactory.decodeByteArray(bytes, 0, bytes.length));
        }
        else {
            imageView.setImageResource(R.drawable.icon_image_picker);
        }
    }

    private String buildPackMeta(IconPackManager.StoredIconPack pack) {
        ArrayList<String> lines = new ArrayList<>();
        if (!pack.author.trim().isEmpty()) lines.add(getString(R.string.icon_pack_author_format, pack.author));
        lines.add(getString(R.string.icon_pack_icon_count_format, pack.icons.size()));
        lines.add(getString(R.string.icon_pack_size_format, StringUtils.formatBytes(pack.packSize)));
        return android.text.TextUtils.join("\n", lines);
    }

    private static String getImportErrorMessage(Exception e) {
        String message = e.getMessage();
        if (message == null || message.trim().isEmpty()) return e.getClass().getSimpleName();
        return message;
    }
}
