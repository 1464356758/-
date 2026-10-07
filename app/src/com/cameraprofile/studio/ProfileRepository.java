package com.cameraprofile.studio;

import android.content.Context;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;

/** JSON-backed catalog, validated at load and again at export. */
public final class ProfileRepository {
  private final LinkedHashMap<String, JSONObject> profiles = new LinkedHashMap<>();

  public ProfileRepository(Context context) throws Exception {
    this(
        new JSONArray(
            new String(
                Io.read(context.getAssets().open("profiles.json"), 1024 * 1024),
                StandardCharsets.UTF_8)));
  }

  public ProfileRepository(JSONArray catalog) throws Exception {
    if (catalog.length() == 0) throw new IOException("设备档案为空");
    for (int i = 0; i < catalog.length(); i++) {
      JSONObject profile = catalog.getJSONObject(i);
      String id = profile.getString("id");
      if (profiles.containsKey(id)) throw new IOException("重复设备档案");
      if (profile.getString("manufacturer").isEmpty()
          || profile.getString("model").isEmpty()
          || profile.getJSONArray("lenses").length() == 0
          || profile.getJSONArray("output_modes").length() == 0)
        throw new IOException("设备档案缺少必要字段");
      profiles.put(id, profile);
    }
  }

  public List<JSONObject> all() {
    return new ArrayList<>(profiles.values());
  }

  public JSONObject get(String id) {
    JSONObject value = profiles.get(id);
    if (value == null) throw new IllegalArgumentException("设备档案不存在");
    return value;
  }

  public JSONObject lens(ExportSettings settings) {
    JSONObject lens = get(settings.profileId).optJSONArray("lenses").optJSONObject(settings.lens);
    if (lens == null) throw new IllegalArgumentException("所选镜头不存在");
    return lens;
  }

  public JSONObject resolution(ExportSettings settings) {
    if (settings.originalSize) {
      try {
        return new JSONObject()
            .put("label", "保持原图尺寸")
            .put("basis", "original_dimensions");
      } catch (JSONException impossible) {
        throw new IllegalStateException(impossible);
      }
    }
    JSONObject target =
        get(settings.profileId).optJSONArray("output_modes").optJSONObject(settings.resolution);
    if (target == null) throw new IllegalArgumentException("所选输出尺寸不存在");
    return target;
  }

  public boolean supports(ExportSettings settings, int mode) {
    JSONObject target = get(settings.profileId).optJSONArray("output_modes").optJSONObject(mode);
    if (target == null) return false;
    if ((long) target.optInt("width") * target.optInt("height") > MemoryBudget.MAX_OUTPUT_PIXELS)
      return false;
    JSONArray lenses = target.optJSONArray("lens_indices");
    if (lenses == null) return true;
    for (int n = 0; n < lenses.length(); n++)
      if (lenses.optInt(n, -1) == settings.lens) return true;
    return false;
  }

  public void validate(ExportSettings settings) {
    JSONObject profile = get(settings.profileId);
    lens(settings);
    if (!"rebuild".equals(settings.mode) && !"metadata".equals(settings.mode))
      throw new IllegalArgumentException("处理模式无效");
    if ("rebuild".equals(settings.mode) && !settings.originalSize
        && !supports(settings, settings.resolution))
      throw new IllegalArgumentException("该镜头不支持所选尺寸，或尺寸超过本版上限");
    if (settings.quality < 90
        || settings.quality > 100
        || settings.strength < 0
        || settings.strength > 100) throw new IllegalArgumentException("质量或 AI 强度无效");
    settings.time();
    JSONObject limits = profile.optJSONObject("exposure_limits");
    if (settings.iso != null || settings.exposure != null || settings.bias != null) {
      if (limits == null) throw new IllegalArgumentException("该设备曝光范围未核验，不能写入手动曝光参数");
      if (settings.iso != null
          && (settings.iso < limits.optInt("iso_min") || settings.iso > limits.optInt("iso_max")))
        throw new IllegalArgumentException("ISO 超出设备的已核验范围");
      if (settings.exposure != null
          && (!Double.isFinite(settings.exposure)
              || settings.exposure < limits.optDouble("shutter_min")
              || settings.exposure > limits.optDouble("shutter_max")))
        throw new IllegalArgumentException("快门超出设备的已核验范围");
      if (settings.bias != null
          && (!Double.isFinite(settings.bias)
              || Math.abs(settings.bias) > limits.optDouble("bias_max")))
        throw new IllegalArgumentException("曝光补偿超出设备范围");
    }
    if (settings.whiteBalance != null && settings.whiteBalance != 0 && settings.whiteBalance != 1)
      throw new IllegalArgumentException("白平衡参数无效");
  }
}
