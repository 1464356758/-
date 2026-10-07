package com.cameraprofile.studio;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.*;
import android.graphics.drawable.*;
import android.net.Uri;
import android.os.*;
import android.provider.OpenableColumns;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** Five focused screens. All file processing belongs to the foreground service. */
public final class MainActivity extends Activity {
  private static final int IMPORT = 10, IMPORT_ALBUM = 11, SAVE_PHOTO = 20, SAVE_ZIP = 21;
  private final int ink = Color.rgb(25, 34, 52),
      muted = Color.rgb(104, 116, 139),
      blue = Color.rgb(65, 85, 218),
      background = Color.rgb(246, 248, 252),
      green = Color.rgb(23, 131, 95),
      red = Color.rgb(177, 58, 63);
  private LinearLayout shell, body, nav;
  private FrameLayout frame;
  private TextView title, queueSummary;
  private final HashMap<String, TextView> jobLabels = new HashMap<>();
  private final HashMap<String, ProgressBar> jobBars = new HashMap<>();
  private String page = "home", resultId = "", queueFingerprint = "", currentBatch = "";
  private String pendingExport = "";
  private ArrayList<String> pendingZipIds = new ArrayList<>();
  private final ArrayList<Uri> inputs = new ArrayList<>();
  private final ArrayList<String> names = new ArrayList<>();
  private ProfileRepository profiles;
  private TaskStore store;
  private ExportSettings settings = new ExportSettings();
  private SharedPreferences preferences;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private final ExecutorService io = Executors.newSingleThreadExecutor();
  private volatile boolean exportBusy;
  private boolean alive, visible;
  private final Runnable poll =
      new Runnable() {
        public void run() {
          if (!alive || !visible) return;
          if ("queue".equals(page)) updateQueue();
          handler.postDelayed(this, 800);
        }
      };

  public void onCreate(Bundle state) {
    super.onCreate(state);
    alive = true;
    getWindow().setStatusBarColor(Color.TRANSPARENT);
    getWindow().setNavigationBarColor(Color.WHITE);
    getWindow()
        .getDecorView()
        .setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
    if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
    try {
      preferences = getSharedPreferences("studio-v2", MODE_PRIVATE);
      profiles = new ProfileRepository(this);
      store = TaskStore.get(this);
      restoreEditor();
      if (state != null) {
        page = state.getString("page", "home");
        resultId = state.getString("result", "");
        pendingExport = state.getString("export", "");
        ArrayList<String> saved = state.getStringArrayList("zip");
        if (saved != null) pendingZipIds = saved;
      }
      consume(getIntent());
      layout();
      render();
    } catch (Exception error) {
      TextView failure = new TextView(this);
      failure.setPadding(dp(24), dp(72), dp(24), dp(24));
      failure.setText("无法打开工作记录\n" + error.getMessage() + "\n原照片不会被覆盖。");
      setContentView(failure);
    }
  }

  protected void onNewIntent(Intent intent) {
    super.onNewIntent(intent);
    setIntent(intent);
    consume(intent);
    render();
  }

  private void consume(Intent intent) {
    if (intent == null) return;
    if ("queue".equals(intent.getStringExtra("page"))) page = "queue";
    else if ("results".equals(intent.getStringExtra("page"))) page = "results";
    String action = intent.getAction();
    ArrayList<Uri> incoming = new ArrayList<>();
    if (Intent.ACTION_SEND.equals(action)) {
      Uri uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
      if (uri != null) incoming.add(uri);
    } else if (Intent.ACTION_SEND_MULTIPLE.equals(action)) {
      ArrayList<Uri> list = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
      if (list != null) incoming.addAll(list);
    }
    if (!incoming.isEmpty()) importUris(incoming, intent.getFlags());
  }

  protected void onSaveInstanceState(Bundle state) {
    super.onSaveInstanceState(state);
    state.putString("page", page);
    state.putString("result", resultId);
    state.putString("export", pendingExport);
    state.putStringArrayList("zip", pendingZipIds);
  }

  protected void onResume() {
    super.onResume();
    visible = true;
    handler.removeCallbacks(poll);
    if (alive && store != null) handler.post(poll);
  }

  protected void onStop() {
    visible = false;
    handler.removeCallbacks(poll);
    super.onStop();
  }

  protected void onDestroy() {
    alive = false;
    handler.removeCallbacks(poll);
    io.shutdownNow();
    super.onDestroy();
  }

  public void onBackPressed() {
    if ("device".equals(page)) {
      page = "editor";
      render();
    } else if ("results".equals(page) && !resultId.isEmpty()) {
      resultId = "";
      render();
    } else if (!"home".equals(page)) {
      page = "home";
      render();
    } else super.onBackPressed();
  }

  private int dp(float value) {
    return Math.round(value * getResources().getDisplayMetrics().density);
  }

  private TextView label(String value, int size, int color) {
    TextView view = new TextView(this);
    view.setText(value);
    view.setTextSize(size);
    view.setTextColor(color);
    view.setFontFeatureSettings("kern");
    view.setLineSpacing(dp(3), 1);
    return view;
  }

  private TextView heading(String value, int size) {
    TextView text = label(value, size, ink);
    text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    return text;
  }

  private LinearLayout column() {
    LinearLayout row = new LinearLayout(this);
    row.setOrientation(LinearLayout.VERTICAL);
    return row;
  }

  private GradientDrawable shape(int color, int radius) {
    GradientDrawable drawable = new GradientDrawable();
    drawable.setColor(color);
    drawable.setCornerRadius(dp(radius));
    return drawable;
  }

  private void space(LinearLayout target, int height) {
    View view = new View(this);
    target.addView(view, new LinearLayout.LayoutParams(1, dp(height)));
  }

  private LinearLayout card(LinearLayout target) {
    LinearLayout card = column();
    card.setPadding(dp(18), dp(18), dp(18), dp(18));
    card.setBackground(shape(Color.WHITE, 18));
    card.setElevation(dp(1));
    LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-1, -2);
    layout.bottomMargin = dp(12);
    target.addView(card, layout);
    return card;
  }

  private Button button(String value, boolean primary, Runnable action) {
    Button button = new Button(this);
    button.setText(value);
    button.setAllCaps(false);
    button.setTextSize(15);
    button.setTypeface(Typeface.create("sans-serif-medium", 0));
    button.setTextColor(primary ? Color.WHITE : blue);
    button.setBackground(
        new RippleDrawable(
            ColorStateList.valueOf(0x224155da),
            shape(primary ? blue : Color.rgb(233, 237, 253), 14),
            null));
    button.setMinHeight(dp(50));
    button.setMinimumHeight(dp(50));
    button.setPadding(dp(12), 0, dp(12), 0);
    button.setOnClickListener(v -> action.run());
    return button;
  }

  private void addButton(LinearLayout target, String value, boolean primary, Runnable action) {
    Button button = button(value, primary, action);
    LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-1, dp(52));
    layout.topMargin = dp(8);
    target.addView(button, layout);
  }

  private void row(LinearLayout target, String key, String value, Runnable action) {
    LinearLayout row = new LinearLayout(this);
    row.setOrientation(LinearLayout.HORIZONTAL);
    row.setGravity(Gravity.CENTER_VERTICAL);
    row.setPadding(dp(16), dp(17), dp(16), dp(17));
    row.setBackground(shape(Color.WHITE, 14));
    LinearLayout left = column();
    left.addView(label(key, 12, muted));
    space(left, 4);
    left.addView(heading(value, 16));
    row.addView(left, new LinearLayout.LayoutParams(0, -2, 1));
    if (action != null) {
      row.addView(label("›", 28, muted));
      row.setOnClickListener(v -> action.run());
      row.setFocusable(true);
    }
    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
    params.bottomMargin = dp(9);
    target.addView(row, params);
  }

  private void layout() {
    shell = column();
    shell.setBackgroundColor(background);
    shell.setOnApplyWindowInsetsListener(
        (view, insets) -> {
          int top, bottom;
          if (Build.VERSION.SDK_INT >= 30) {
            android.graphics.Insets safe = insets.getInsets(WindowInsets.Type.systemBars());
            top = safe.top;
            bottom = safe.bottom;
          } else {
            top = insets.getSystemWindowInsetTop();
            bottom = insets.getSystemWindowInsetBottom();
          }
          shell.setPadding(0, top, 0, bottom);
          return insets;
        });
    LinearLayout header = new LinearLayout(this);
    header.setGravity(Gravity.CENTER_VERTICAL);
    header.setPadding(dp(20), dp(16), dp(20), dp(12));
    TextView brand = heading("CP", 19);
    brand.setTextColor(blue);
    header.addView(brand);
    title = heading("  CAMERA PROFILE", 12);
    header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
    TextView info = label("2.1  ·  关于", 12, muted);
    info.setPadding(dp(10), dp(8), 0, dp(8));
    info.setOnClickListener(v -> about());
    header.addView(info);
    shell.addView(header);
    frame = new FrameLayout(this);
    shell.addView(frame, new LinearLayout.LayoutParams(-1, 0, 1));
    nav = new LinearLayout(this);
    nav.setGravity(Gravity.CENTER);
    nav.setPadding(dp(10), dp(8), dp(10), dp(8));
    nav.setBackgroundColor(Color.WHITE);
    shell.addView(nav, new LinearLayout.LayoutParams(-1, dp(62)));
    setContentView(shell);
    shell.requestApplyInsets();
  }

  private void render() {
    if (frame == null) return;
    frame.removeAllViews();
    jobLabels.clear();
    jobBars.clear();
    queueFingerprint = "";
    LinearLayout area = column();
    frame.addView(area, new FrameLayout.LayoutParams(-1, -1));
    ScrollView scroll = new ScrollView(this);
    scroll.setFillViewport(true);
    body = column();
    body.setPadding(dp(20), dp(8), dp(20), dp(24));
    scroll.addView(body);
    area.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    if ("editor".equals(page)) {
      editor();
      LinearLayout footer = column();
      footer.setPadding(dp(20), dp(6), dp(20), dp(14));
      addButton(footer, ProcessingService.active ? "添加到处理队列" : "处理并保存新副本", true, () -> enqueue());
      area.addView(footer);
    } else if ("device".equals(page)) devices();
    else if ("queue".equals(page)) queue();
    else if ("results".equals(page)) results();
    else home();
    nav.removeAllViews();
    String[] values = {"首页", "处理队列", "结果"};
    String[] pages = {"home", "queue", "results"};
    for (int i = 0; i < values.length; i++) {
      final String destination = pages[i];
      TextView item = heading(values[i], 14);
      item.setGravity(Gravity.CENTER);
      boolean selected =
          page.equals(destination)
              || destination.equals("home") && (page.equals("editor") || page.equals("device"));
      item.setTextColor(selected ? blue : muted);
      item.setBackground(shape(selected ? 0xffeef0fd : Color.WHITE, 12));
      item.setOnClickListener(
          v -> {
            page = destination;
            resultId = "";
            render();
          });
      nav.addView(item, new LinearLayout.LayoutParams(0, -1, 1));
    }
  }

  private void home() {
    body.addView(label("PRIVATE PHOTO WORKSPACE", 11, blue));
    space(body, 12);
    body.addView(heading("照片，重新整理。", 30));
    space(body, 8);
    body.addView(label("选择设备档案，保护原图，生成经过文件校验的新副本。", 15, muted));
    space(body, 20);
    LinearLayout hero = card(body);
    hero.addView(new CameraGlyph(this), new LinearLayout.LayoutParams(-1, dp(110)));
    addButton(hero, "＋  导入照片", true, () -> pick());
    hero.addView(label("支持批量选择 · 相册、文件与其他 App 分享", 12, muted));
    space(hero, 4);
    if (!inputs.isEmpty())
      addButton(
          body,
          "继续编辑已选 " + inputs.size() + " 张照片",
          false,
          () -> {
            page = "editor";
            render();
          });
    LinearLayout assurances = card(body);
    assurances.addView(heading("本地处理  ·  原图保护", 16));
    space(assurances, 5);
    assurances.addView(label("无需账号。输入只读，结果另存。GPS 与普通来源字段默认清理。", 13, muted));
    space(body, 8);
    body.addView(heading("常用设备", 19));
    space(body, 12);
    for (JSONObject profile : orderedProfiles()) {
      if (favorite(profile) || profile.optString("id").equals(settings.profileId)) {
        row(
            body,
            profile.optString("manufacturer"),
            (favorite(profile) ? "★  " : "") + display(profile),
            () -> {
              selectProfile(profile.optString("id"));
              page = inputs.isEmpty() ? "device" : "editor";
              render();
            });
      }
    }
    addButton(
        body,
        "浏览全部 " + profiles.all().size() + " 款设备",
        false,
        () -> {
          page = "device";
          render();
        });
    if (!store.notice().isEmpty()) body.addView(label(store.notice(), 13, red));
  }

  private void editor() {
    body.addView(heading("编辑照片", 26));
    space(body, 5);
    body.addView(label(inputs.size() + " 张已选 · 原文件始终保留", 13, muted));
    space(body, 14);
    LinearLayout preview = card(body);
    ImageView image = new ImageView(this);
    image.setScaleType(ImageView.ScaleType.FIT_CENTER);
    image.setBackground(shape(0xfff2f4f8, 12));
    preview.addView(image, new LinearLayout.LayoutParams(-1, dp(220)));
    if (!inputs.isEmpty()) loadImage(image, inputs.get(0));
    else preview.addView(label("请先导入照片", 14, muted));
    LinearLayout links = new LinearLayout(this);
    TextView replace = label("重新选择", 13, blue);
    replace.setPadding(0, dp(12), dp(20), 0);
    replace.setOnClickListener(v -> pick());
    links.addView(replace);
    TextView list = label("查看所选照片", 13, blue);
    list.setPadding(0, dp(12), 0, 0);
    list.setOnClickListener(v -> inputList());
    links.addView(list);
    preview.addView(links);
    JSONObject profile = profiles.get(settings.profileId);
    row(
        body,
        "设备档案",
        display(profile),
        () -> {
          page = "device";
          render();
        });
    row(body, "镜头 / 光圈", profiles.lens(settings).optString("name"), () -> lensPicker());
    row(
        body,
        "处理模式",
        "metadata".equals(settings.mode) ? "极速 · 仅重建元数据" : "像素重建 · 按目标尺寸",
        () -> modePicker());
    if (!"metadata".equals(settings.mode)) {
      row(body, "输出尺寸", profiles.resolution(settings).optString("label"), () -> resolutionPicker());
      body.addView(label(basis(profiles.resolution(settings)), 12, muted));
      space(body, 12);
    }
    row(body, "隐私清理", "GPS 关闭 · 不复制旧来源字段", null);
    row(
        body,
        "高级设置",
        settings.ai ? "AI 细节预测 " + settings.strength + "%" : "质量、时间与可选 AI",
        () -> advanced());
    body.addView(label("设备档案用于参数模拟。像素重建保留构图；比例不同时加白边。", 12, muted));
  }

  private List<JSONObject> orderedProfiles() {
    List<JSONObject> list = profiles.all();
    list.sort((a, b) -> Boolean.compare(favorite(b), favorite(a)));
    return list;
  }

  private boolean favorite(JSONObject profile) {
    String id = profile.optString("id");
    return preferences.getBoolean("favorite-" + id, getPreferences(0).getBoolean(id, false));
  }

  private String display(JSONObject profile) {
    return profile.optString("display", profile.optString("model"));
  }

  private void devices() {
    body.addView(heading("选择设备", 26));
    space(body, 6);
    body.addView(label("收藏会置顶。镜头和尺寸来自独立设备档案。", 13, muted));
    space(body, 18);
    for (JSONObject profile : orderedProfiles()) {
      LinearLayout card = card(body);
      LinearLayout top = new LinearLayout(this);
      top.setGravity(Gravity.CENTER_VERTICAL);
      TextView name = heading(display(profile), 18);
      top.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
      TextView star = label(favorite(profile) ? "★" : "☆", 25, blue);
      star.setMinWidth(dp(48));
      star.setMinHeight(dp(48));
      star.setGravity(Gravity.CENTER);
      star.setOnClickListener(
          v -> {
            preferences
                .edit()
                .putBoolean("favorite-" + profile.optString("id"), !favorite(profile))
                .apply();
            render();
          });
      top.addView(star);
      card.addView(top);
      space(card, 7);
      card.addView(
          label(
              profile.optString("manufacturer") + "  ·  " + profile.optString("category"),
              13,
              muted));
      addButton(
          card,
          profile.optString("id").equals(settings.profileId) ? "已选择 · 使用此设备" : "使用此设备",
          true,
          () -> {
            selectProfile(profile.optString("id"));
            page = inputs.isEmpty() ? "home" : "editor";
            render();
          });
      TextView source = label("镜头、尺寸与资料来源  ›", 12, blue);
      source.setPadding(0, dp(10), 0, 0);
      source.setOnClickListener(v -> profileInfo(profile));
      card.addView(source);
    }
  }

  private void selectProfile(String id) {
    settings.profileId = id;
    settings.lens = 0;
    settings.resolution = 0;
    settings.iso = null;
    settings.exposure = null;
    settings.bias = null;
    settings.whiteBalance = null;
    normalizeResolution();
    persistEditor();
  }

  private void normalizeResolution() {
    if (!profiles.supports(settings, settings.resolution)) {
      JSONArray choices = profiles.get(settings.profileId).optJSONArray("output_modes");
      for (int i = 0; i < choices.length(); i++)
        if (profiles.supports(settings, i)) {
          settings.resolution = i;
          return;
        }
    }
  }

  private void lensPicker() {
    JSONArray lenses = profiles.get(settings.profileId).optJSONArray("lenses");
    String[] labels = new String[lenses.length()];
    for (int i = 0; i < labels.length; i++) labels[i] = lenses.optJSONObject(i).optString("name");
    new AlertDialog.Builder(this)
        .setTitle("镜头与光圈")
        .setSingleChoiceItems(
            labels,
            settings.lens,
            (dialog, which) -> {
              settings.lens = which;
              normalizeResolution();
              persistEditor();
              dialog.dismiss();
              render();
            })
        .setNegativeButton("返回", null)
        .show();
  }

  private void resolutionPicker() {
    JSONArray choices = profiles.get(settings.profileId).optJSONArray("output_modes");
    ArrayList<Integer> indices = new ArrayList<>();
    ArrayList<String> labels = new ArrayList<>();
    int selected = 0;
    for (int i = 0; i < choices.length(); i++)
      if (profiles.supports(settings, i)) {
        if (i == settings.resolution) selected = indices.size();
        indices.add(i);
        labels.add(choices.optJSONObject(i).optString("label"));
      }
    new AlertDialog.Builder(this)
        .setTitle("目标尺寸 · 横竖自动匹配")
        .setSingleChoiceItems(
            labels.toArray(new String[0]),
            selected,
            (dialog, which) -> {
              settings.resolution = indices.get(which);
              persistEditor();
              dialog.dismiss();
              render();
            })
        .setNegativeButton("返回", null)
        .show();
  }

  private void modePicker() {
    String[] labels = {"极速：只重建元数据（需要 JPEG）", "重建：按所选尺寸重构像素"};
    new AlertDialog.Builder(this)
        .setTitle("处理模式")
        .setSingleChoiceItems(
            labels,
            "metadata".equals(settings.mode) ? 0 : 1,
            (dialog, which) -> {
              settings.mode = which == 0 ? "metadata" : "rebuild";
              persistEditor();
              dialog.dismiss();
              render();
            })
        .setNegativeButton("返回", null)
        .show();
  }

  private String basis(JSONObject target) {
    String basis = target.optString("basis");
    if ((long) target.optInt("width") * target.optInt("height") > MemoryBudget.MAX_OUTPUT_PIXELS)
      return "设备规格尺寸；超过本版 52MP 上限，暂不提供处理。";
    if (basis.contains("official")) return "官方 JPEG 尺寸。能否处理仍取决于手机可用内存。";
    if (basis.contains("sample_dimensions")) return "已核对样张尺寸；实际设备输出会随拍摄模式变化。";
    return "兼容输出预设；该尺寸的未处理原片尚未核实。";
  }

  private Spinner spinner(LinearLayout container, String name, String[] values, int selected) {
    container.addView(label(name, 13, muted));
    Spinner spinner = new Spinner(this);
    spinner.setAdapter(
        new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, values));
    spinner.setSelection(Math.max(0, selected));
    container.addView(spinner, new LinearLayout.LayoutParams(-1, dp(50)));
    space(container, 10);
    return spinner;
  }

  private EditText field(
      LinearLayout container, String name, String value, String hint, int inputType) {
    container.addView(label(name, 13, muted));
    EditText edit = new EditText(this);
    edit.setSingleLine(true);
    edit.setText(value);
    edit.setTextSize(15);
    edit.setHint(hint);
    edit.setInputType(inputType);
    container.addView(edit);
    space(container, 10);
    return edit;
  }

  private String number(Number value) {
    return value == null ? "" : String.valueOf(value);
  }

  private void advanced() {
    LinearLayout form = column();
    form.setPadding(dp(22), dp(12), dp(22), dp(24));
    ScrollView scroll = new ScrollView(this);
    scroll.addView(form);
    int qualityIndex =
        settings.quality == 100 ? 1 : settings.quality == 95 ? 2 : settings.quality == 90 ? 3 : 0;
    Spinner quality =
        spinner(
            form,
            "JPEG 质量",
            new String[] {"高质量 · 98", "最高质量 · 100", "均衡 · 95", "较小文件 · 90"},
            qualityIndex);
    Switch ai = new Switch(this);
    ai.setText("离线 AI 细节预测");
    ai.setChecked(settings.ai);
    form.addView(ai);
    space(form, 7);
    form.addView(label("ESRGAN 神经网络使用本机 CPU。可能较慢，文字和纹理可能变化；不会还原未知真实细节。输入上限 400 万像素。", 12, muted));
    space(form, 10);
    Spinner strength =
        spinner(
            form,
            "AI 强度",
            new String[] {"0%", "25% · 保守", "50%", "75%", "100%"},
            settings.strength / 25);
    Switch detail = new Switch(this);
    detail.setText("轻度细节增强");
    detail.setChecked(settings.detail);
    form.addView(detail);
    space(form, 16);
    int ti = "none".equals(settings.timeMode) ? 1 : "custom".equals(settings.timeMode) ? 2 : 0;
    Spinner time = spinner(form, "写入时间", new String[] {"当前时间与手机时区", "不写入时间", "自定义时间与时区"}, ti);
    String defaultDate = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
    EditText date =
        field(
            form,
            "自定义日期",
            settings.customDate.isEmpty() ? defaultDate : settings.customDate,
            "2026-10-06 19:00:00",
            1);
    EditText zone = field(form, "自定义时区", settings.customZone, "+08:00", 1);
    form.addView(label("以下为手动参数模拟，留空表示不写入。手机曝光范围尚未核验，ISO、快门和补偿不可设置。", 12, muted));
    space(form, 12);
    EditText iso =
        field(form, "ISO", number(settings.iso), "留空", android.text.InputType.TYPE_CLASS_NUMBER);
    EditText shutter = field(form, "快门（秒或分数）", number(settings.exposure), "例如 1/125 或 0.008", 1);
    EditText bias =
        field(
            form,
            "曝光补偿 EV",
            number(settings.bias),
            "例如 -0.3",
            android.text.InputType.TYPE_CLASS_NUMBER
                | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
                | android.text.InputType.TYPE_NUMBER_FLAG_SIGNED);
    boolean exposureSupported = profiles.get(settings.profileId).has("exposure_limits");
    iso.setEnabled(exposureSupported);
    shutter.setEnabled(exposureSupported);
    bias.setEnabled(exposureSupported);
    Spinner wb =
        spinner(
            form,
            "白平衡字段",
            new String[] {"不写入", "自动（模拟）", "手动（模拟）"},
            settings.whiteBalance == null ? 0 : settings.whiteBalance + 1);
    TextView error = label("", 13, red);
    form.addView(error);
    AlertDialog dialog =
        new AlertDialog.Builder(this)
            .setTitle("高级设置")
            .setView(scroll)
            .setNegativeButton("返回", null)
            .setPositiveButton("保存设置", null)
            .create();
    dialog.setOnShowListener(
        d ->
            dialog
                .getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(
                    v -> {
                      try {
                        ExportSettings next = ExportSettings.parse(settings.json());
                        next.quality =
                            new int[] {98, 100, 95, 90}[quality.getSelectedItemPosition()];
                        next.ai = ai.isChecked();
                        next.strength = strength.getSelectedItemPosition() * 25;
                        next.detail = detail.isChecked();
                        next.timeMode =
                            new String[] {"current", "none", "custom"}
                                [time.getSelectedItemPosition()];
                        next.customDate = date.getText().toString().trim();
                        next.customZone = zone.getText().toString().trim();
                        if (exposureSupported) {
                          next.iso = integer(iso.getText().toString());
                          next.exposure = seconds(shutter.getText().toString());
                          next.bias = decimal(bias.getText().toString());
                        }
                        next.whiteBalance =
                            wb.getSelectedItemPosition() == 0
                                ? null
                                : wb.getSelectedItemPosition() - 1;
                        profiles.validate(next);
                        settings = next;
                        persistEditor();
                        dialog.dismiss();
                        render();
                      } catch (Exception invalid) {
                        error.setText("设置未保存：" + invalid.getMessage());
                      }
                    }));
    dialog.show();
  }

  private static Integer integer(String text) {
    return text.trim().isEmpty() ? null : Integer.valueOf(text.trim());
  }

  private static Double decimal(String text) {
    return text.trim().isEmpty() ? null : Double.valueOf(text.trim());
  }

  private static Double seconds(String text) {
    text = text.trim();
    if (text.isEmpty()) return null;
    String[] values = text.split("/", -1);
    if (values.length == 1) return Double.valueOf(values[0]);
    if (values.length != 2) throw new IllegalArgumentException("快门格式应为秒数或 1/125");
    double numerator = Double.parseDouble(values[0]), denominator = Double.parseDouble(values[1]);
    if (denominator <= 0) throw new IllegalArgumentException("快门分母必须大于 0");
    return numerator / denominator;
  }

  private void pick() {
    new AlertDialog.Builder(this)
        .setTitle("从哪里导入照片？")
        .setItems(new String[] {"从相册选择", "从文件夹选择"},
            (dialog, which) -> launchPicker(which == 0))
        .setNegativeButton("取消", null).show();
  }

  private void launchPicker(boolean album) {
    try {
      startActivityForResult(album ? ImportPicker.album(this) : ImportPicker.files(),
          album ? IMPORT_ALBUM : IMPORT);
    } catch (ActivityNotFoundException error) {
      message(album ? "手机未提供相册选择器，请从文件夹选择" : "手机未提供文件选择器，请从相册选择");
    }
  }

  protected void onActivityResult(int request, int result, Intent data) {
    super.onActivityResult(request, result, data);
    if (result != RESULT_OK || data == null || data.getData() == null && data.getClipData() == null)
      return;
    if (request == IMPORT || request == IMPORT_ALBUM) {
      ArrayList<Uri> selected = new ArrayList<>();
      if (data.getClipData() != null) {
        for (int i = 0; i < data.getClipData().getItemCount(); i++)
          selected.add(data.getClipData().getItemAt(i).getUri());
      } else selected.add(data.getData());
      importUris(selected, data.getFlags());
      render();
    } else if (request == SAVE_PHOTO && data.getData() != null) documentPhoto(data.getData());
    else if (request == SAVE_ZIP && data.getData() != null) documentZip(data.getData());
  }

  private void importUris(List<Uri> selected, int flags) {
    if (selected.size() > 100) {
      message("单批最多 100 张，请分批导入");
      return;
    }
    inputs.clear();
    names.clear();
    HashSet<String> unique = new HashSet<>();
    for (Uri uri : selected) {
      if (uri == null || !"content".equals(uri.getScheme()) || !unique.add(uri.toString()))
        continue;
      try {
        getContentResolver()
            .takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
      } catch (SecurityException temporary) {
      }
      inputs.add(uri);
      names.add(fileName(uri));
    }
    if (inputs.isEmpty()) {
      message("没有可读取的照片");
      return;
    }
    page = "editor";
    persistEditor();
  }

  private String fileName(Uri uri) {
    try (Cursor cursor =
        getContentResolver()
            .query(uri, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
      if (cursor != null && cursor.moveToFirst()) {
        String name = cursor.getString(0);
        if (name != null) return name.length() > 150 ? name.substring(0, 150) : name;
      }
    } catch (Exception unsupported) {
    }
    return "照片 " + (inputs.size() + 1);
  }

  private void enqueue() {
    try {
      profiles.validate(settings);
      currentBatch = store.enqueue(inputs, names, settings);
      persistEditor();
      startQueue();
      page = "queue";
      render();
    } catch (Exception error) {
      message(error.getMessage());
    }
  }

  private void startQueue() {
    if (Build.VERSION.SDK_INT >= 33
        && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        && !preferences.getBoolean("notification_requested", false)) {
      preferences.edit().putBoolean("notification_requested", true).apply();
      requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, 30);
    }
    Intent service = new Intent(this, ProcessingService.class);
    List<TaskStore.Job> jobs = store.snapshot();
    ClipData clip = null;
    for (TaskStore.Job job : jobs)
      if (TaskStore.QUEUED.equals(job.state) || TaskStore.RUNNING.equals(job.state)) {
        Uri uri = Uri.parse(job.input);
        if (clip == null) clip = ClipData.newUri(getContentResolver(), "照片来源", uri);
        else clip.addItem(new ClipData.Item(uri));
      }
    if (clip != null) {
      service.setClipData(clip);
      service.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
    }
    try {
      startForegroundService(service);
    } catch (Exception error) {
      try { store.interruptActive("后台队列未启动，可重试：" + error.getMessage()); } catch (Exception ignored) {}
      message("后台队列未启动：" + error.getMessage());
    }
  }

  private void queue() {
    body.addView(heading("处理队列", 26));
    space(body, 8);
    queueSummary = label("", 14, muted);
    body.addView(queueSummary);
    body.addView(label(backgroundStatus(), 12, muted));
    addButton(body, "通知与后台运行设置", false, () -> backgroundSettings());
    space(body, 15);
    List<TaskStore.Job> jobs = store.snapshot();
    for (int i = jobs.size() - 1; i >= 0; i--) {
      TaskStore.Job job = jobs.get(i);
      LinearLayout card = card(body);
      card.addView(heading(job.name, 15));
      space(card, 6);
      TextView state = label("", 13, muted);
      card.addView(state);
      jobLabels.put(job.id, state);
      ProgressBar progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
      progress.setMax(100);
      progress.setProgressTintList(ColorStateList.valueOf(blue));
      space(card, 6);
      card.addView(progress);
      jobBars.put(job.id, progress);
      if (TaskStore.SUCCESS.equals(job.state))
        addButton(
            card,
            "查看验证结果",
            false,
            () -> {
              resultId = job.id;
              page = "results";
              render();
            });
      else if (!TaskStore.QUEUED.equals(job.state) && !TaskStore.RUNNING.equals(job.state))
        addButton(card, "调整设置后重新处理", false, () -> editFailed(job));
    }
    if (jobs.isEmpty()) body.addView(label("还没有任务。导入照片后点击处理即可。", 14, muted));
    if (ProcessingService.active)
      addButton(
          body,
          "取消未完成任务",
          false,
          () -> {
            startService(
                new Intent(this, ProcessingService.class).setAction(ProcessingService.CANCEL));
          });
    else {
      addButton(
          body,
          "继续 / 重试未完成任务",
          true,
          () -> {
            try {
              store.retry();
              startQueue();
              render();
            } catch (Exception error) {
              message(error.getMessage());
            }
          });
    }
    addButton(
        body,
        "查看已保存结果",
        false,
        () -> {
          resultId = "";
          page = "results";
          render();
        });
    queueFingerprint = fingerprint(jobs);
    updateQueue();
  }

  private String fingerprint(List<TaskStore.Job> jobs) {
    StringBuilder signature = new StringBuilder(ProcessingService.active ? "1" : "0");
    for (TaskStore.Job job : jobs) signature.append(job.id).append(job.state);
    return signature.toString();
  }

  private void updateQueue() {
    if (!"queue".equals(page) || store == null) return;
    List<TaskStore.Job> jobs = store.snapshot();
    if (!queueFingerprint.equals(fingerprint(jobs))) {
      render();
      return;
    }
    int good = 0, waiting = 0, failed = 0;
    for (TaskStore.Job job : jobs) {
      if (TaskStore.SUCCESS.equals(job.state)) good++;
      else if (TaskStore.RUNNING.equals(job.state) || TaskStore.QUEUED.equals(job.state)) waiting++;
      else failed++;
      TextView label = jobLabels.get(job.id);
      if (label != null) {
        String state = TaskStore.SUCCESS.equals(job.state) ? "文件验证通过 · 已保存新副本" : job.stage;
        if (!job.error.isEmpty()) state += "\n" + job.error;
        label.setText(state);
        label.setTextColor(
            TaskStore.SUCCESS.equals(job.state) ? green : job.error.isEmpty() ? muted : red);
      }
      ProgressBar bar = jobBars.get(job.id);
      if (bar != null) {
        bar.setProgress(job.progress);
        bar.setVisibility(
            TaskStore.QUEUED.equals(job.state) || TaskStore.RUNNING.equals(job.state)
                ? View.VISIBLE
                : View.GONE);
      }
    }
    String batchId = currentBatch;
    if (batchId.isEmpty() && !jobs.isEmpty()) batchId = jobs.get(jobs.size() - 1).batch;
    int batchTotal = 0, batchGood = 0, processingIndex = 0;
    for (TaskStore.Job job : jobs)
      if (batchId.equals(job.batch)) {
        batchTotal++;
        if (TaskStore.SUCCESS.equals(job.state)) batchGood++;
        if (TaskStore.RUNNING.equals(job.state)) processingIndex = batchTotal;
      }
    String batchLine =
        batchTotal == 0
            ? ""
            : (processingIndex > 0
                    ? "正在处理 " + processingIndex + " / " + batchTotal + "  ·  "
                    : "最近批次  ·  ")
                + "成功 "
                + batchGood
                + " / "
                + batchTotal
                + "\n";
    if (queueSummary != null)
      queueSummary.setText(
          batchLine
              + "已保存 "
              + good
              + "  ·  待处理 "
              + waiting
              + "  ·  未完成 "
              + failed
              + (ProcessingService.active ? "\n本机处理运行中 · 已用 " + ProcessingService.elapsedText() : "")
              + (ProcessingService.fatal.isEmpty() ? "" : "\n" + ProcessingService.fatal));
  }

  private String backgroundStatus() {
    PowerManager power = (PowerManager)getSystemService(POWER_SERVICE);
    NotificationManager manager = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
    return (manager.areNotificationsEnabled() ? "进度通知已开启" : "进度通知未开启")
        + " · " + (power.isIgnoringBatteryOptimizations(getPackageName()) ? "电池优化已关闭" : "系统电池优化开启")
        + (power.isPowerSaveMode() ? " · 省电模式可能降低速度" : "");
  }

  private void backgroundSettings() {
    new AlertDialog.Builder(this).setTitle("通知与后台运行设置")
        .setItems(new String[] {"App 电池与后台运行", "处理进度通知", "系统电池优化名单"},
            (dialog, which) -> {
              Intent intent;
              if (which == 1) intent = new Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                  .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, getPackageName());
              else if (which == 2) intent = new Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
              else intent = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                  Uri.parse("package:" + getPackageName()));
              try { startActivity(intent); }
              catch (ActivityNotFoundException unavailable) { message("请在系统设置中为 Camera Profile 允许通知与后台运行"); }
            }).setNegativeButton("关闭", null).show();
  }

  private void editFailed(TaskStore.Job job) {
    inputs.clear();
    names.clear();
    inputs.add(Uri.parse(job.input));
    names.add(job.name);
    try {
      settings = ExportSettings.parse(job.settings.json());
    } catch (Exception ignored) {
    }
    page = "editor";
    persistEditor();
    render();
  }

  private List<TaskStore.Job> successful() {
    ArrayList<TaskStore.Job> values = new ArrayList<>();
    for (TaskStore.Job job : store.snapshot())
      if (TaskStore.SUCCESS.equals(job.state)) values.add(job);
    return values;
  }

  private void results() {
    if (!resultId.isEmpty()) {
      TaskStore.Job job = store.find(resultId);
      if (job != null && TaskStore.SUCCESS.equals(job.state)) {
        result(job);
        return;
      }
      resultId = "";
    }
    body.addView(heading("已保存结果", 26));
    space(body, 6);
    body.addView(label("保留经过校验的文件，可直接导出或打包。", 13, muted));
    space(body, 16);
    List<TaskStore.Job> values = successful();
    if (values.isEmpty()) body.addView(label("处理完成后，照片和验证信息会出现在这里。", 14, muted));
    for (int i = values.size() - 1; i >= 0; i--) {
      TaskStore.Job job = values.get(i);
      JSONObject report = job.report;
      row(
          body,
          "文件验证通过 · " + report.optInt("width") + " × " + report.optInt("height"),
          report.optString("device"),
          () -> {
            resultId = job.id;
            render();
          });
    }
    if (!values.isEmpty()) addButton(body, "导出全部已验证照片 ZIP", true, () -> requestZip(values));
    addButton(
        body,
        "清理本地工作记录",
        false,
        () -> {
          if (ProcessingService.active) {
            message("请先结束处理队列");
            return;
          }
          new AlertDialog.Builder(this)
              .setTitle("清理本地记录")
              .setMessage("删除 App 内的工作记录与重复副本。相册里已保存的照片保留。")
              .setNegativeButton("返回", null)
              .setPositiveButton(
                  "清理",
                  (d, w) -> {
                    try {
                      store.clear();
                      render();
                    } catch (Exception error) {
                      message(error.getMessage());
                    }
                  })
              .show();
        });
  }

  private void result(TaskStore.Job job) {
    JSONObject report = job.report;
    body.addView(heading("文件验证通过", 26));
    space(body, 7);
    body.addView(label("已另存新照片 · Device Profile 参数模拟", 13, green));
    space(body, 16);
    LinearLayout photo = card(body);
    ImageView image = new ImageView(this);
    image.setScaleType(ImageView.ScaleType.FIT_CENTER);
    photo.addView(image, new LinearLayout.LayoutParams(-1, dp(240)));
    loadImage(image, ResultProvider.uri(job.id));
    JSONObject tags = report.optJSONObject("metadata");
    row(body, "设备", report.optString("device"), null);
    row(body, "镜头", report.optString("lens"), null);
    row(
        body,
        "像素尺寸 / 文件大小",
        report.optInt("width") + " × " + report.optInt("height") + "  ·  " + size(job.bytes),
        null);
    row(
        body,
        "ISO / 快门",
        tags.optString("ISOSpeedRatings", "未配置") + "  /  " + tags.optString("ExposureTime", "未配置"),
        null);
    row(
        body,
        "日期 / 时区",
        tags.optString("DateTimeOriginal", "不写入时间")
            + "  "
            + tags.optString("OffsetTimeOriginal", ""),
        null);
    row(body, "处理算法", report.optString("algorithm"), null);
    row(body, "GPS / 文件校验", "已清理  /  SHA-256 回读一致", null);
    body.addView(label("像素与编码来自处理流程。验证通过表示文件和配置一致，不是相机采集认证。", 12, muted));
    addButton(body, "直接导出 JPEG 文件", true, () -> requestPhoto(job));
    addButton(body, "分享照片", false, () -> share(job));
    addButton(
        body,
        "查看完整验证报告",
        false,
        () -> {
          TextView text = label(report.toString(), 12, ink);
          text.setTextIsSelectable(true);
          text.setPadding(dp(20), dp(12), dp(20), dp(20));
          ScrollView scroll = new ScrollView(this);
          scroll.addView(text);
          new AlertDialog.Builder(this)
              .setTitle("文件验证报告")
              .setView(scroll)
              .setPositiveButton("返回", null)
              .show();
        });
    TextView hash = label("SHA-256\n" + job.sha, 11, muted);
    hash.setTextIsSelectable(true);
    space(body, 16);
    body.addView(hash);
  }

  private String size(long bytes) {
    return String.format(Locale.US, "%.2f MB", bytes / 1048576.0);
  }

  private void requestPhoto(TaskStore.Job job) {
    if (exportBusy) return;
    pendingExport = job.id;
    Intent intent =
        new Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("image/jpeg")
            .putExtra(Intent.EXTRA_TITLE, job.report.optString("file"));
    startActivityForResult(intent, SAVE_PHOTO);
  }

  private void requestZip(List<TaskStore.Job> jobs) {
    if (exportBusy) return;
    pendingZipIds.clear();
    for (TaskStore.Job job : jobs) pendingZipIds.add(job.id);
    Intent intent =
        new Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/zip")
            .putExtra(Intent.EXTRA_TITLE, "CameraProfile_" + System.currentTimeMillis() + ".zip");
    startActivityForResult(intent, SAVE_ZIP);
  }

  private void documentPhoto(Uri target) {
    TaskStore.Job job = store.find(pendingExport);
    if (job == null) {
      message("原任务不可用，请重新选择结果");
      return;
    }
    exportBusy = true;
    Toast.makeText(this, "正在导出并回读文件", Toast.LENGTH_SHORT).show();
    io.execute(
        () -> {
          try {
            Exports.photo(this, store, job, target);
            onUi(() -> message("JPEG 已导出，SHA-256 与已验证副本一致。"));
          } catch (Exception error) {
            onUi(() -> message("导出未完成：" + error.getMessage()));
          } finally {
            exportBusy = false;
          }
        });
  }

  private void documentZip(Uri target) {
    ArrayList<TaskStore.Job> jobs = new ArrayList<>();
    for (String id : pendingZipIds) {
      TaskStore.Job job = store.find(id);
      if (job != null) jobs.add(job);
    }
    exportBusy = true;
    Toast.makeText(this, "正在打包并逐张回读", Toast.LENGTH_SHORT).show();
    io.execute(
        () -> {
          try {
            Exports.zip(this, store, jobs, target);
            onUi(() -> message("ZIP 已导出，每张照片及报告均通过哈希与 CRC 校验。"));
          } catch (Exception error) {
            onUi(() -> message("ZIP 未完成：" + error.getMessage()));
          } finally {
            exportBusy = false;
          }
        });
  }

  private void share(TaskStore.Job job) {
    Intent intent =
        new Intent(Intent.ACTION_SEND)
            .setType("image/jpeg")
            .putExtra(Intent.EXTRA_STREAM, ResultProvider.uri(job.id));
    intent.setClipData(ClipData.newRawUri("已验证照片", ResultProvider.uri(job.id)));
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
    try {
      startActivity(Intent.createChooser(intent, "分享新照片"));
    } catch (ActivityNotFoundException error) {
      message("手机没有可接收图片的应用");
    }
  }

  private void inputList() {
    new AlertDialog.Builder(this)
        .setTitle("所选 " + inputs.size() + " 张照片")
        .setItems(
            names.toArray(new String[0]),
            (dialog, index) -> {
              if (index > 0) {
                Collections.swap(inputs, 0, index);
                Collections.swap(names, 0, index);
                persistEditor();
                render();
              }
            })
        .setNegativeButton("返回", null)
        .show();
  }

  private void loadImage(ImageView view, Uri uri) {
    view.setTag(uri.toString());
    io.execute(
        () -> {
          Bitmap bitmap = null;
          try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
              BitmapFactory.decodeStream(in, null, options);
            }
            int orientation = 1;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
              orientation =
                  JpegEngine.normalizeOrientation(
                      new android.media.ExifInterface(in).getAttributeInt("Orientation", 1));
            } catch (Exception unsupported) {
            }
            options.inJustDecodeBounds = false;
            options.inSampleSize = 1;
            while (Math.max(options.outWidth, options.outHeight) / options.inSampleSize > 900)
              options.inSampleSize *= 2;
            options.inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB);
            try (InputStream in = getContentResolver().openInputStream(uri)) {
              bitmap = BitmapFactory.decodeStream(in, null, options);
            }
            if (bitmap != null && orientation != 1) {
              int width = bitmap.getWidth(), height = bitmap.getHeight();
              Bitmap oriented =
                  Bitmap.createBitmap(
                      orientation >= 5 ? height : width,
                      orientation >= 5 ? width : height,
                      Bitmap.Config.ARGB_8888);
              Matrix matrix = new Matrix();
              matrix.setValues(PixelOrientation.matrix(orientation, width, height));
              new Canvas(oriented).drawBitmap(bitmap, matrix, new Paint());
              bitmap.recycle();
              bitmap = oriented;
            }
            final Bitmap ready = bitmap;
            onUi(
                () -> {
                  if (ready != null
                      && uri.toString().equals(view.getTag())
                      && view.isAttachedToWindow()) view.setImageBitmap(ready);
                  else if (ready != null) ready.recycle();
                });
          } catch (Exception | OutOfMemoryError invalid) {
            if (bitmap != null) bitmap.recycle();
          }
        });
  }

  private void onUi(Runnable action) {
    handler.post(
        () -> {
          if (alive && !isFinishing()) action.run();
        });
  }

  private void restoreEditor() {
    try {
      String json = preferences.getString("editor", "");
      if (!json.isEmpty()) {
        JSONObject saved = new JSONObject(json);
        settings = ExportSettings.parse(saved.getJSONObject("settings"));
        JSONArray files = saved.optJSONArray("inputs");
        if (files != null)
          for (int i = 0; i < files.length(); i++) {
            JSONObject file = files.getJSONObject(i);
            inputs.add(Uri.parse(file.getString("uri")));
            names.add(file.getString("name"));
          }
      } else {
        List<JSONObject> all = profiles.all();
        int recent = getPreferences(0).getInt("recent", 0);
        if (recent >= 0 && recent < all.size())
          settings.profileId = all.get(recent).optString("id");
      }
      profiles.get(settings.profileId);
      normalizeResolution();
      profiles.validate(settings);
    } catch (Exception invalid) {
      inputs.clear();
      names.clear();
      settings = new ExportSettings();
    }
  }

  private void persistEditor() {
    try {
      JSONArray files = new JSONArray();
      for (int i = 0; i < inputs.size(); i++)
        files.put(new JSONObject().put("uri", inputs.get(i).toString()).put("name", names.get(i)));
      preferences
          .edit()
          .putString(
              "editor",
              new JSONObject().put("settings", settings.json()).put("inputs", files).toString())
          .apply();
    } catch (Exception error) {
      message("编辑设置无法保存：" + error.getMessage());
    }
  }

  private void profileInfo(JSONObject profile) {
    StringBuilder text = new StringBuilder("设备档案 v" + profile.optInt("version") + "\n\n镜头与光圈\n");
    JSONArray lenses = profile.optJSONArray("lenses");
    for (int i = 0; i < lenses.length(); i++)
      text.append("• ").append(lenses.optJSONObject(i).optString("name")).append("\n");
    text.append("\n尺寸依据与核验状态\n");
    JSONArray modes = profile.optJSONArray("output_modes");
    for (int i = 0; i < modes.length(); i++) {
      JSONObject mode = modes.optJSONObject(i);
      text.append(mode.optInt("width"))
          .append(" × ")
          .append(mode.optInt("height"))
          .append("\n")
          .append(basis(mode))
          .append("\n");
    }
    text.append("\n资料来源\n")
        .append(profile.optString("source"))
        .append("\n\n未核验的 ISO、快门、物理焦距不自动写入；不复制厂商私有数据或真实性凭证。");
    new AlertDialog.Builder(this)
        .setTitle(display(profile))
        .setMessage(text)
        .setPositiveButton("返回", null)
        .show();
  }

  private void about() {
    new AlertDialog.Builder(this)
        .setTitle("Camera Profile Studio 2.1")
        .setMessage(
            "Android 10+ · 离线运行\n\n"
                + "极速模式：JPEG 元数据重建，保留压缩图像数据与原 ICC。\n"
                + "重建模式：支持系统可解码 JPEG、PNG、WebP、HEIC；输出 JPEG / sRGB SDR，透明区域填白。HEIC 导出尚未实现。\n\n"
                + "可选 ESRGAN 离线 AI，使用手机 CPU；本版未启用 GPU/NPU。AI 输入上限 400 万像素；普通输入和输出上限 5200"
                + " 万像素，仍受可用内存限制。\n\n"
                + "设备数据用于模拟与兼容性测试。不能证明照片真实来自所选设备，也不能保证通过真实性鉴定。普通来源元数据会清理，像素中的水印或隐藏标记不保证消除。识别到 APP11"
                + " / C2PA 等特征时停止处理，检测不构成完整凭证鉴定。\n\n"
                + "使用前景通知持续处理；系统结束 App 后可恢复未完成任务。强制停止不会自动重启。导出目录：Pictures/CameraProfile。\n\n"
                + "开源组件：Google LiteRT 1.4.2（Apache 2.0）；ESRGAN TensorFlow 示例模型与 Adrish Dey"
                + " 的实现（MIT）。")
        .setPositiveButton("知道了", null)
        .show();
  }

  private void message(String text) {
    if (!alive || isFinishing()) return;
    new AlertDialog.Builder(this)
        .setMessage(text == null ? "操作未完成" : text)
        .setPositiveButton("知道了", null)
        .show();
  }

  private final class CameraGlyph extends View {
    final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    CameraGlyph(Context context) {
      super(context);
    }

    protected void onDraw(Canvas canvas) {
      float center = getWidth() / 2f, cy = getHeight() / 2f;
      paint.setColor(0xffeef0fd);
      canvas.drawCircle(center, cy, dp(48), paint);
      paint.setColor(blue);
      paint.setStyle(Paint.Style.STROKE);
      paint.setStrokeWidth(dp(3));
      canvas.drawRoundRect(
          center - dp(31), cy - dp(20), center + dp(31), cy + dp(24), dp(10), dp(10), paint);
      canvas.drawCircle(center, cy + dp(2), dp(13), paint);
      canvas.drawLine(center - dp(16), cy - dp(20), center - dp(10), cy - dp(28), paint);
      canvas.drawLine(center - dp(10), cy - dp(28), center + dp(10), cy - dp(28), paint);
      canvas.drawLine(center + dp(10), cy - dp(28), center + dp(16), cy - dp(20), paint);
      paint.setStyle(Paint.Style.FILL);
      canvas.drawCircle(center + dp(22), cy - dp(10), dp(2), paint);
    }
  }
}
