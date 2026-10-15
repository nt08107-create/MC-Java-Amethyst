package net.kdt.pojavlaunch.fragments;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.database.Cursor;
import android.app.AlertDialog;
import android.widget.Toast;
import androidx.activity.result.contract.ActivityResultContracts;
import net.kdt.pojavlaunch.utils.GimmyModFiles;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.Locale;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.Bundle;
import android.util.Base64;
import android.util.Base64OutputStream;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatCheckBox;
import androidx.fragment.app.Fragment;

import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.extra.ExtraConstants;
import net.kdt.pojavlaunch.extra.ExtraCore;
import net.kdt.pojavlaunch.multirt.MultiRTUtils;
import net.kdt.pojavlaunch.multirt.RTSpinnerAdapter;
import net.kdt.pojavlaunch.multirt.Runtime;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.profiles.ProfileIconCache;
import net.kdt.pojavlaunch.profiles.VersionSelectorDialog;
import net.kdt.pojavlaunch.utils.CropperUtils;
import net.kdt.pojavlaunch.value.launcherprofiles.LauncherProfiles;
import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class ProfileEditorFragment extends Fragment implements CropperUtils.CropperListener{
    public static final String TAG = "ProfileEditorFragment";
    public static final String DELETED_PROFILE = "deleted_profile";

    private String mProfileKey;
    private MinecraftProfile mTempProfile = null;
    private String mValueToConsume = "";
    private Button mSaveButton, mDeleteButton, mControlSelectButton, mGameDirButton, mVersionSelectButton;
    private Spinner mDefaultRuntime, mDefaultRenderer;
    private CheckBox mUseANGLE;
    private EditText mDefaultName, mDefaultJvmArgument;
    private TextView mDefaultPath, mDefaultVersion, mDefaultControl;
    private ImageView mProfileIcon;
    private final ActivityResultLauncher<Intent> mModPicker = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == android.app.Activity.RESULT_OK && result.getData() != null
                        && result.getData().getData() != null) importPickedMod(result.getData().getData());
            });
    private final ActivityResultLauncher<String> mSkinPicker = registerForActivityResult(
            new ActivityResultContracts.GetContent(), uri -> {
                if (uri != null) previewSkin(uri);
            });
    private final ActivityResultLauncher<?> mCropperLauncher = CropperUtils.registerCropper(this, this);

    private List<String> mRenderNames;

    public ProfileEditorFragment(){
        super(R.layout.fragment_profile_editor);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        // Paths, which can be changed
        String value = (String) ExtraCore.consumeValue(ExtraConstants.FILE_SELECTOR);
        if(value != null){
            if(mValueToConsume.equals(FileSelectorFragment.BUNDLE_SELECT_FOLDER)){
                mTempProfile.gameDir = value;
            }else{
                mTempProfile.controlFile = value;
            }
        }
        return super.onCreateView(inflater, container, savedInstanceState);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        bindViews(view);

        Tools.RenderersList renderersList = Tools.getCompatibleRenderers(view.getContext());
        mRenderNames = renderersList.rendererIds;
        List<String> renderList = new ArrayList<>(renderersList.rendererDisplayNames.length + 1);
        renderList.addAll(Arrays.asList(renderersList.rendererDisplayNames));
        renderList.add(view.getContext().getString(R.string.global_default));
        mDefaultRenderer.setAdapter(new ArrayAdapter<>(getContext(), R.layout.item_simple_list_1, renderList));

        // Set up behaviors
        mSaveButton.setOnClickListener(v -> {
            ProfileIconCache.dropIcon(mProfileKey);
            save();
            Tools.backToMainMenu(requireActivity());
        });

        mDeleteButton.setOnClickListener(v -> {
            if(LauncherProfiles.mainProfileJson.profiles.size() > 1){
                ProfileIconCache.dropIcon(mProfileKey);
                LauncherProfiles.mainProfileJson.profiles.remove(mProfileKey);
                LauncherProfiles.write();
                ExtraCore.setValue(ExtraConstants.REFRESH_VERSION_SPINNER, DELETED_PROFILE);
            }

            Tools.removeCurrentFragment(requireActivity());
        });


        View.OnClickListener gameDirListener = getGameDirListener();
        mGameDirButton.setOnClickListener(gameDirListener);
        view.findViewById(R.id.vprof_editor_gimmy_mods).setOnClickListener(v -> showGimmyMods());
        view.findViewById(R.id.vprof_editor_gimmy_isolate).setOnClickListener(v -> isolateVersion());
        view.findViewById(R.id.vprof_editor_gimmy_skin).setOnClickListener(v -> mSkinPicker.launch("image/png"));
        mDefaultPath.setOnClickListener(gameDirListener);

        View.OnClickListener controlSelectListener = getControlSelectListener();
        mControlSelectButton.setOnClickListener(controlSelectListener);
        mDefaultControl.setOnClickListener(controlSelectListener);

        // Setup the expendable list behavior
        View.OnClickListener versionSelectListener = getVersionSelectListener();
        mVersionSelectButton.setOnClickListener(versionSelectListener);
        mDefaultVersion.setOnClickListener(versionSelectListener);

        mUseANGLE.setOnClickListener(v -> mTempProfile.useANGLE = ((CheckBox) v).isChecked());

        // Set up the icon change click listener
        mProfileIcon.setOnClickListener(v -> CropperUtils.startCropper(mCropperLauncher));

        loadValues(LauncherPreferences.DEFAULT_PREF.getString(LauncherPreferences.PREF_KEY_CURRENT_PROFILE, ""), view.getContext());
    }

    /** A new dedicated game directory per selected version, without copying/moving existing saves. */
    private void isolateVersion() {
        String version = mDefaultVersion.getText().toString().trim();
        if (version.isEmpty() || !version.matches("[A-Za-z0-9._+\\-]{1,100}") || version.equals(".") || version.equals("..")) {
            new AlertDialog.Builder(requireContext()).setMessage("Select a valid installed version first.")
                    .setPositiveButton(android.R.string.ok, null).show();
            return;
        }
        File target = new File(new File(Tools.DIR_GAME_HOME, "gimmy-versions"), version);
        new AlertDialog.Builder(requireContext()).setTitle("Isolate this version?")
                .setMessage("Set this profile to its own game directory? Existing worlds and MODs will NOT be copied or moved. Save the profile to apply.\n" + target)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("Use separate folder", (dialog, which) -> {
                    if (!target.isDirectory() && !target.mkdirs()) {
                        new AlertDialog.Builder(requireContext()).setMessage("Could not create directory")
                                .setPositiveButton(android.R.string.ok, null).show();
                        return;
                    }
                    mDefaultPath.setText(target.getAbsolutePath());
                    mTempProfile.gameDir = target.getAbsolutePath();
                }).show();
    }

    /** Launcher-only preview; does not claim to change the skin in Minecraft. */
    private void previewSkin(Uri uri) {
        try (InputStream stream = requireContext().getContentResolver().openInputStream(uri)) {
            if (stream == null) throw new IOException("Cannot open PNG");
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeStream(stream, null, options);
            int w = options.outWidth, h = options.outHeight;
            if (w <= 0 || h <= 0 || w > 1024 || h > 1024 || w != 64 || (h != 32 && h != 64))
                throw new IOException("Select a 64x64 or legacy 64x32 Minecraft PNG skin");
        } catch (Exception e) { showModError(e); return; }
        try (InputStream stream = requireContext().getContentResolver().openInputStream(uri)) {
            Bitmap bitmap = BitmapFactory.decodeStream(stream);
            if (bitmap == null) throw new IOException("Invalid image");
            ImageView preview = new ImageView(requireContext());
            preview.setImageBitmap(bitmap);
            preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
            int padding = (int) (16 * getResources().getDisplayMetrics().density);
            preview.setPadding(padding, padding, padding, padding);
            new AlertDialog.Builder(requireContext()).setTitle("Minecraft PNG skin preview")
                    .setMessage("Preview only: this does not replace your game skin.")
                    .setView(preview).setPositiveButton(android.R.string.ok, null).show();
        } catch (Exception e) { showModError(e); }
    }

    /** Gimmy local MOD manager; uses the currently edited profile, including unsaved path changes. */
    private File gimmyModsDir() {
        String path = mDefaultPath.getText().toString().trim();
        File gameDir = path.isEmpty() ? new File(Tools.DIR_GAME_HOME) : new File(path);
        return GimmyModFiles.modsDirectory(gameDir);
    }

    private void showGimmyMods() {
        File dir = gimmyModsDir();
        List<File> mods = GimmyModFiles.list(dir);
        String[] labels = new String[mods.size()];
        for (int i = 0; i < mods.size(); i++) {
            String name = mods.get(i).getName();
            labels[i] = (name.endsWith(".jar.disabled") ? "[OFF] " : "[ON] ") + name;
        }
        new AlertDialog.Builder(requireContext())
                .setTitle("Gimmy MODs: " + dir.getAbsolutePath())
                .setItems(labels, (dialog, which) -> showModActions(dir, mods.get(which).getName()))
                .setPositiveButton("Import .jar", (dialog, which) -> {
                    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("*/*"); // Some Android providers don't expose JAR MIME types.
                    mModPicker.launch(intent);
                })
                .setNegativeButton("Close", null)
                .show();
    }

    private void showModActions(File dir, String filename) {
        String action = filename.endsWith(".jar.disabled") ? "Enable" : "Disable";
        new AlertDialog.Builder(requireContext()).setTitle(filename)
                .setItems(new String[]{action, "Remove"}, (dialog, which) -> {
                    if (which == 0) {
                        try {
                            GimmyModFiles.toggle(dir, filename);
                            showGimmyMods();
                        } catch (IOException e) { showModError(e); }
                    } else {
                        new AlertDialog.Builder(requireContext()).setTitle("Remove " + filename + "?")
                                .setMessage("This deletes the selected MOD file from this profile.")
                                .setPositiveButton("Remove", (confirm, button) -> {
                                    try { GimmyModFiles.remove(dir, filename); showGimmyMods(); }
                                    catch (IOException e) { showModError(e); }
                                }).setNegativeButton("Cancel", null).show();
                    }
                }).setNegativeButton("Cancel", null).show();
    }

    private void importPickedMod(Uri uri) {
        String name = null;
        try (Cursor cursor = requireContext().getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) name = cursor.getString(0);
        } catch (RuntimeException e) { showModError(e); return; }
        if (name == null || name.contains("/") || name.contains("\\") || name.equals(".") || name.equals("..")
                || name.indexOf('\0') >= 0 || !name.toLowerCase(Locale.ROOT).endsWith(".jar")) {
            showModError(new IOException("Select a .jar document")); return;
        }
        final String filename = name;
        final File destinationDir = gimmyModsDir();
        final Context appContext = requireContext().getApplicationContext();
        new Thread(() -> {
            File temp = null;
            try {
                temp = File.createTempFile("gimmy-import-", ".jar", appContext.getCacheDir());
                try (InputStream in = appContext.getContentResolver().openInputStream(uri);
                     OutputStream out = Files.newOutputStream(temp.toPath())) {
                    if (in == null) throw new IOException("Cannot read selected document");
                    byte[] buffer = new byte[8192]; int n;
                    while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
                }
                if (!destinationDir.isDirectory() && !destinationDir.mkdirs()) throw new IOException("Cannot create MOD folder");
                // Preserve the picker display name, and never silently overwrite existing MODs.
                File target = new File(destinationDir, filename);
                if (!target.getCanonicalFile().getParentFile().equals(destinationDir.getCanonicalFile()))
                    throw new IOException("Invalid MOD file name");
                Files.copy(temp.toPath(), target.toPath());
                if (isAdded()) requireActivity().runOnUiThread(() -> {
                    Toast.makeText(requireContext(), "Imported " + filename, Toast.LENGTH_SHORT).show();
                    showGimmyMods();
                });
            } catch (Exception e) {
                if (isAdded()) requireActivity().runOnUiThread(() -> showModError(e));
            } finally { if (temp != null) temp.delete(); }
        }, "GimmyModImport").start();
    }

    private void showModError(Exception e) {
        if (isAdded()) new AlertDialog.Builder(requireContext()).setTitle("MOD operation failed")
                .setMessage(e.getMessage()).setPositiveButton("OK", null).show();
    }

    private View.OnClickListener getGameDirListener() {
        return v -> {
            Bundle bundle = new Bundle(2);
            bundle.putBoolean(FileSelectorFragment.BUNDLE_SELECT_FOLDER, true);
            bundle.putString(FileSelectorFragment.BUNDLE_ROOT_PATH, Tools.DIR_GAME_HOME);
            bundle.putBoolean(FileSelectorFragment.BUNDLE_SHOW_FILE, false);
            mValueToConsume = FileSelectorFragment.BUNDLE_SELECT_FOLDER;

            Tools.swapFragment(requireActivity(),
                    FileSelectorFragment.class, FileSelectorFragment.TAG, bundle);
        };
    }

    private View.OnClickListener getControlSelectListener() {
        return v -> {
            Bundle bundle = new Bundle(3);
            bundle.putBoolean(FileSelectorFragment.BUNDLE_SELECT_FOLDER, false);
            bundle.putString(FileSelectorFragment.BUNDLE_ROOT_PATH, Tools.CTRLMAP_PATH);
            mValueToConsume = FileSelectorFragment.BUNDLE_SELECT_FILE;

            Tools.swapFragment(requireActivity(),
                    FileSelectorFragment.class, FileSelectorFragment.TAG, bundle);
        };
    }

    private View.OnClickListener getVersionSelectListener() {
        return v -> VersionSelectorDialog.open(v.getContext(), false, (id, snapshot)-> {
            mTempProfile.lastVersionId = id;
            mDefaultVersion.setText(id);
        });
    }

    private void loadValues(@NonNull String profile, @NonNull Context context){
        if(mTempProfile == null){
            mTempProfile = getProfile(profile);
        }
        // TODO: Remove this jank when it's not relevant anymore
        // Shitty hack to make OSMZink smoothly transition into kopper
        if ("vulkan_zink".equals(mTempProfile.pojavRendererName)) mTempProfile.pojavRendererName = "opengles3_desktopgl_zink_kopper";
        mProfileIcon.setImageDrawable(
                ProfileIconCache.fetchIcon(getResources(), mProfileKey, mTempProfile.icon)
        );

        // Runtime spinner
        List<Runtime> runtimes = MultiRTUtils.getInstalledRuntimes();
        int jvmIndex = runtimes.indexOf(new Runtime("<Default>"));
        if (mTempProfile.javaDir != null) {
            String selectedRuntime = mTempProfile.javaDir.substring(Tools.LAUNCHERPROFILES_RTPREFIX.length());
            int nindex = runtimes.indexOf(new Runtime(selectedRuntime));
            if (nindex != -1) jvmIndex = nindex;
        }
        mDefaultRuntime.setAdapter(new RTSpinnerAdapter(context, runtimes));
        if(jvmIndex == -1) jvmIndex = runtimes.size() - 1;
        mDefaultRuntime.setSelection(jvmIndex);

        // Renderer spinner
        int rendererIndex = mDefaultRenderer.getAdapter().getCount() - 1;
        if(mTempProfile.pojavRendererName != null) {
            int nindex = mRenderNames.indexOf(mTempProfile.pojavRendererName);
            if(nindex != -1) rendererIndex = nindex;
        }
        mDefaultRenderer.setSelection(rendererIndex);

        mDefaultVersion.setText(mTempProfile.lastVersionId);
        mDefaultJvmArgument.setText(mTempProfile.javaArgs == null ? "" : mTempProfile.javaArgs);
        mDefaultName.setText(mTempProfile.name);
        mDefaultPath.setText(mTempProfile.gameDir == null ? "" : mTempProfile.gameDir);
        mDefaultControl.setText(mTempProfile.controlFile == null ? "" : mTempProfile.controlFile);
        mUseANGLE.setChecked(mTempProfile.useANGLE);
    }

    private MinecraftProfile getProfile(@NonNull String profile){
        MinecraftProfile minecraftProfile;
        if(getArguments() == null) {
            // EDGE CASE: User leaves Pojav in background. Pojav gets terminated in the background.
            // Current selected fragment and its arguments are saved.
            // User returns to Pojav. Android restarts process and reinitializes fragment without
            // going to the main screen. mainProfileJson and profiles left uninitialized, which
            // results in a crash.
            // Reload the profiles to avoid this edge case.
            LauncherProfiles.load();
            MinecraftProfile originalProfile = LauncherProfiles.mainProfileJson.profiles.get(profile);
            // EDGE CASE: User edits the JSON, so the profile that was edited no longer exists.
            // Create a brand new profile as a fallback for this case.
            if(originalProfile != null) minecraftProfile = new MinecraftProfile(originalProfile);
            else minecraftProfile = MinecraftProfile.createTemplate();
            mProfileKey = profile;
        }else{
            minecraftProfile = MinecraftProfile.createTemplate();
            mProfileKey = LauncherProfiles.getFreeProfileKey();
        }
        return minecraftProfile;
    }


    private void bindViews(@NonNull View view){
        mDefaultControl = view.findViewById(R.id.vprof_editor_ctrl_spinner);
        mDefaultRuntime = view.findViewById(R.id.vprof_editor_spinner_runtime);
        mDefaultRenderer = view.findViewById(R.id.vprof_editor_profile_renderer);
        mUseANGLE = view.findViewById(R.id.vprof_editor_profile_angle);
        mDefaultVersion = view.findViewById(R.id.vprof_editor_version_spinner);

        mDefaultPath = view.findViewById(R.id.vprof_editor_path);
        mDefaultName = view.findViewById(R.id.vprof_editor_profile_name);
        mDefaultJvmArgument = view.findViewById(R.id.vprof_editor_jre_args);

        mSaveButton = view.findViewById(R.id.vprof_editor_save_button);
        mDeleteButton = view.findViewById(R.id.vprof_editor_delete_button);
        mControlSelectButton = view.findViewById(R.id.vprof_editor_ctrl_button);
        mVersionSelectButton = view.findViewById(R.id.vprof_editor_version_button);
        mGameDirButton = view.findViewById(R.id.vprof_editor_path_button);
        mProfileIcon = view.findViewById(R.id.vprof_editor_profile_icon);
    }

    private void save(){
        //First, check for potential issues in the inputs
        mTempProfile.lastVersionId = mDefaultVersion.getText().toString();
        mTempProfile.controlFile = mDefaultControl.getText().toString();
        mTempProfile.name = mDefaultName.getText().toString();
        mTempProfile.javaArgs = mDefaultJvmArgument.getText().toString()
                .replaceAll("[\r\n]+", " ")
                .trim();
        mTempProfile.gameDir = mDefaultPath.getText().toString();

        if(mTempProfile.controlFile.isEmpty()) mTempProfile.controlFile = null;
        if(mTempProfile.javaArgs.isEmpty()) mTempProfile.javaArgs = null;
        if(mTempProfile.gameDir.isEmpty()) mTempProfile.gameDir = null;

        Runtime selectedRuntime = (Runtime) mDefaultRuntime.getSelectedItem();
        mTempProfile.javaDir = (selectedRuntime.name.equals("<Default>") || selectedRuntime.versionString == null)
                ? null : Tools.LAUNCHERPROFILES_RTPREFIX + selectedRuntime.name;

        if(mDefaultRenderer.getSelectedItemPosition() == mRenderNames.size()) mTempProfile.pojavRendererName = null;
        else mTempProfile.pojavRendererName = mRenderNames.get(mDefaultRenderer.getSelectedItemPosition());


        LauncherProfiles.mainProfileJson.profiles.put(mProfileKey, mTempProfile);
        LauncherProfiles.write();
        ExtraCore.setValue(ExtraConstants.REFRESH_VERSION_SPINNER, mProfileKey);
    }

    @Override
    public void onCropped(Bitmap contentBitmap) {
        mProfileIcon.setImageBitmap(contentBitmap);
        Log.i("bitmap", "w="+contentBitmap.getWidth() +" h="+contentBitmap.getHeight());
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        try (Base64OutputStream base64OutputStream = new Base64OutputStream(byteArrayOutputStream, Base64.NO_WRAP)) {
            contentBitmap.compress(
                Build.VERSION.SDK_INT < Build.VERSION_CODES.R ?
                    // On Android < 30, there was no distinction between "lossy" and "lossless",
                    // and the type is picked by the quality parameter. We set the quality to 60.
                    // so it should be lossy,
                    Bitmap.CompressFormat.WEBP:
                    // On Android >= 30, we can explicitly specify that we want lossy compression
                    // with the visual quality of 60.
                    Bitmap.CompressFormat.WEBP_LOSSY,
                60,
                base64OutputStream
            );
            base64OutputStream.flush();
            byteArrayOutputStream.flush();
        }catch (IOException e) {
            Tools.showErrorRemote(e);
            return;
        }
        String iconLine = new String(byteArrayOutputStream.toByteArray(), StandardCharsets.UTF_8);
        mTempProfile.icon = "data:image/webp;base64," + iconLine;
    }

    @Override
    public void onFailed(Exception exception) {
        Tools.showErrorRemote(exception);
    }
}
