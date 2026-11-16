/* Spektrafilm local darkroom UI. GPLv3.
 * Standard browser APIs only: no framework, no external request, no eval.
 * All strings inserted into the DOM use textContent, never innerHTML.
 */
(() => {
  "use strict";

  const NEUTRAL_FILM = "kodak_portra_400";
  const NEUTRAL_PRINT = "kodak_portra_endura";
  const MAX_UPLOAD_BYTES = 100 * 1024 * 1024;
  const MAX_FILENAME_CHARS = 160;
  const DEFAULT_EXPORT_QUALITY = 95;
  const DEBOUNCE_MS = 250;
  const EV_MIN = -3;
  const EV_MAX = 3;

  const byId = (id) => document.getElementById(id);

  const el = {
    openButton: byId("open-button"),
    exportButton: byId("export-button"),
    fileInput: byId("file-input"),
    catalogRetry: byId("catalog-retry"),
    catalogStatus: byId("catalog-status"),
    catalogCount: byId("catalog-count"),
    catalogNav: byId("catalog-nav"),
    imageMeta: byId("image-meta"),
    refreshButton: byId("refresh-button"),
    photoStage: byId("photo-stage"),
    emptyState: byId("empty-state"),
    emptyOpen: byId("empty-open"),
    emptyError: byId("empty-error"),
    photoFrame: byId("photo-frame"),
    originalImage: byId("original-image"),
    effectImage: byId("effect-image"),
    compareRange: byId("compare-range"),
    divider: byId("divider"),
    splitOutput: byId("split-output"),
    holdOriginal: byId("hold-original"),
    renderStatus: byId("render-status"),
    currentStyle: byId("current-style"),
    currentSpec: byId("current-spec"),
    recipeHint: byId("recipe-hint"),
    strength: byId("strength"),
    strengthOutput: byId("strength-output"),
    exposure: byId("exposure"),
    exposureOutput: byId("exposure-output"),
    exposureNote: byId("exposure-note"),
    exposureReset: byId("exposure-reset"),
    grain: byId("grain"),
    grainOutput: byId("grain-output"),
    halation: byId("halation"),
    halationOutput: byId("halation-output"),
    filmSelect: byId("film-select"),
    printSelect: byId("print-select"),
    profileReset: byId("profile-reset"),
    profileNote: byId("profile-note"),
    resetButton: byId("reset-button"),
    exportDialog: byId("export-dialog"),
    exportForm: byId("export-form"),
    exportClose: byId("export-close"),
    exportCancel: byId("export-cancel"),
    exportSubmit: byId("export-submit"),
    qualityRow: byId("quality-row"),
    quality: byId("quality"),
    filename: byId("filename"),
    filenamePreview: byId("filename-preview"),
    exportStatus: byId("export-status"),
  };

  const state = {
    token: "",
    catalog: null,
    hasImage: false,
    image: null,
    selectedPresetId: null,
    strength: 1,
    grainScale: 1,
    halationScale: 1,
    exposureOverride: null,
    filmOverride: null,
    printOverride: null,
    split: 50,
    generation: 0,
    sessionId: 0,
    editVersion: 0,
    renderSeq: 0,
    renderInFlight: false,
    renderQueued: false,
    previewCurrent: false,
    lastRendered: null,
    opening: false,
    exporting: false,
    exportRecipe: null,
    catalogLoaded: false,
    selectionInitialized: false,
  };

  let debounceTimer = null;

  // ---------------------------------------------------------------- utilities
  function clamp(value, low, high) {
    return Math.min(high, Math.max(low, value));
  }

  function round(value, digits) {
    const factor = 10 ** digits;
    return Math.round(value * factor) / factor;
  }

  function formatEv(value) {
    const number = Number(value);
    if (!Number.isFinite(number)) return "0.00";
    const sign = number > 0 ? "+" : "";
    return `${sign}${number.toFixed(2)}`;
  }

  function formatPercent(scale) {
    return `${Math.round(scale * 100)}%`;
  }

  function formatBytes(bytes) {
    const number = Number(bytes);
    if (!Number.isFinite(number) || number < 0) return "—";
    if (number < 1024) return `${number} B`;
    if (number < 1024 * 1024) return `${(number / 1024).toFixed(1)} KB`;
    return `${(number / (1024 * 1024)).toFixed(1)} MB`;
  }

  function formatDimensions(width, height) {
    const w = Number(width);
    const h = Number(height);
    if (!Number.isFinite(w) || !Number.isFinite(h) || w <= 0 || h <= 0) {
      return "尺寸未知";
    }
    return `${w} × ${h}`;
  }

  function displayError(error) {
    if (error instanceof TypeError) {
      return "无法连接本地服务，请确认启动器仍在运行。";
    }
    if (error && typeof error.message === "string" && error.message.trim()) {
      return error.message;
    }
    return "发生未知错误。";
  }

  function timestampLabel(date = new Date()) {
    const pad = (value) => String(value).padStart(2, "0");
    return [
      date.getFullYear(),
      pad(date.getMonth() + 1),
      pad(date.getDate()),
      "_",
      pad(date.getHours()),
      pad(date.getMinutes()),
      pad(date.getSeconds()),
    ].join("");
  }

  function sanitizeFilename(value) {
    const text = String(value == null ? "" : value).normalize("NFC");
    let cleaned = "";
    for (const char of text) {
      if (/[\p{L}\p{N}]/u.test(char) || char === " " || char === "_" || char === "-") {
        cleaned += char;
      }
    }
    cleaned = cleaned.replace(/\s+/g, " ").replace(/^[ _-]+|[ _-]+$/g, "");
    if (cleaned.length > MAX_FILENAME_CHARS) {
      cleaned = cleaned.slice(0, MAX_FILENAME_CHARS).replace(/[ _-]+$/g, "");
    }
    return cleaned;
  }

  function extensionFor(format) {
    return format === "png" ? "png" : "jpg";
  }

  function selectedFormat() {
    const checked = el.exportForm.querySelector('input[name="format"]:checked');
    return checked && checked.value === "png" ? "png" : "jpeg";
  }

  function getPreset() {
    if (!state.catalog || !Array.isArray(state.catalog.presets)) return null;
    return state.catalog.presets.find((preset) => preset.id === state.selectedPresetId) || null;
  }

  function getPresetById(id) {
    if (!state.catalog || !Array.isArray(state.catalog.presets)) return null;
    return state.catalog.presets.find((preset) => preset.id === id) || null;
  }

  function stockName(id) {
    if (!state.catalog || !Array.isArray(state.catalog.stocks)) return id;
    const stock = state.catalog.stocks.find((entry) => entry.id === id);
    return stock ? stock.name : id;
  }

  function resolvedProfiles() {
    const preset = getPreset();
    const neutral = state.strength <= 0 || !preset;
    const film =
      state.filmOverride !== null
        ? state.filmOverride
        : neutral
          ? NEUTRAL_FILM
          : preset.filmProfile || NEUTRAL_FILM;
    const print =
      state.printOverride !== null
        ? state.printOverride
        : neutral
          ? NEUTRAL_PRINT
          : preset.printProfile || NEUTRAL_PRINT;
    return { film, print, neutral };
  }

  function authoredExposure() {
    const preset = getPreset();
    const value = preset ? Number(preset.exposureEv) : 0;
    return Number.isFinite(value) ? value : 0;
  }

  function displayedExposure() {
    if (state.exposureOverride !== null) return state.exposureOverride;
    return authoredExposure() * state.strength;
  }

  function buildRecipe() {
    const preset = getPreset();
    const strength = clamp(state.strength, 0, 1);
    const neutral = strength <= 0 || !preset;
    const recipe = {
      presetId: neutral ? "__default__" : preset.id,
      strength: round(strength, 3),
      grainScale: round(state.grainScale, 3),
      halationScale: round(state.halationScale, 3),
    };
    if (state.exposureOverride !== null) {
      recipe.exposureEv = round(clamp(state.exposureOverride, -12, 12), 3);
    }
    if (state.filmOverride !== null) {
      recipe.filmProfile = state.filmOverride;
    } else if (neutral) {
      recipe.filmProfile = NEUTRAL_FILM;
    }
    if (state.printOverride !== null) {
      recipe.printProfile = state.printOverride;
    } else if (neutral) {
      recipe.printProfile = NEUTRAL_PRINT;
    }
    return recipe;
  }

  function currentStyleLabel() {
    const preset = getPreset();
    if (state.strength <= 0) return "中性配方 · 强度 0%";
    if (preset) return `${preset.name} · ${formatPercent(state.strength)}`;
    return "中性配方";
  }

  // ---------------------------------------------------------------- API
  function readToken() {
    let token = "";
    let url = null;
    try {
      url = new URL(window.location.href);
      token = url.searchParams.get("token") || "";
    } catch (_error) {
      return "";
    }
    if (token && url) {
      try {
        url.searchParams.delete("token");
        const next =
          url.pathname +
          (url.searchParams.toString() ? `?${url.searchParams}` : "") +
          url.hash;
        window.history.replaceState(null, "", next);
      } catch (_error) {
        /* The token remains usable even if the address bar cannot be cleaned. */
      }
    }
    return token;
  }

  async function api(path, options = {}) {
    const headers = new Headers(options.headers || {});
    if (state.token) headers.set("X-Spektra-Token", state.token);
    const response = await fetch(path, {
      ...options,
      headers,
      cache: "no-store",
      credentials: "same-origin",
    });
    if (!response.ok) {
      let payload = null;
      const contentType = (response.headers.get("content-type") || "").toLowerCase();
      try {
        if (contentType.includes("application/json")) {
          payload = await response.json();
        } else {
          const text = await response.text();
          if (text) payload = { error: text };
        }
      } catch (_error) {
        payload = null;
      }
      const message =
        payload && typeof payload.error === "string" && payload.error.trim()
          ? payload.error
          : `请求失败（HTTP ${response.status}）`;
      const error = new Error(message);
      error.name = "ApiError";
      error.status = response.status;
      error.code = payload && typeof payload.code === "string" ? payload.code : "http_error";
      error.payload = payload;
      throw error;
    }
    return response;
  }

  async function responseJson(response) {
    try {
      return await response.json();
    } catch (_error) {
      throw new Error("本地服务返回了无法解析的数据。");
    }
  }

  function preloadImage(url) {
    return new Promise((resolve, reject) => {
      const image = new Image();
      image.onload = () => resolve(url);
      image.onerror = () => reject(new Error("本地预览图片加载失败。"));
      image.src = url;
    });
  }

  // ---------------------------------------------------------------- status
  function setCatalogStatus(text, tone = "") {
    el.catalogStatus.textContent = text;
    if (tone) el.catalogStatus.dataset.tone = tone;
    else delete el.catalogStatus.dataset.tone;
  }

  function setRenderStatus(text, tone = "") {
    el.renderStatus.textContent = text;
    if (tone) el.renderStatus.dataset.tone = tone;
    else delete el.renderStatus.dataset.tone;
  }

  function setExportStatus(text, tone = "") {
    el.exportStatus.textContent = text;
    if (tone) el.exportStatus.dataset.tone = tone;
    else delete el.exportStatus.dataset.tone;
  }

  function showOpenError(message) {
    el.emptyError.textContent = message;
    el.emptyError.hidden = false;
  }

  function hideOpenError() {
    el.emptyError.textContent = "";
    el.emptyError.hidden = true;
  }

  // ---------------------------------------------------------------- catalog
  async function loadCatalog() {
    el.catalogRetry.hidden = true;
    el.catalogNav.replaceChildren();
    el.catalogCount.textContent = "正在读取";
    setCatalogStatus("正在读取真实目录…");
    try {
      const response = await api("/api/catalog");
      const data = await responseJson(response);
      if (!data || data.ok !== true) throw new Error("目录响应缺少 ok 标记。");
      state.catalog = data;
      state.catalogLoaded = true;
      const previousPreset = state.selectedPresetId;
      buildCatalog();
      populateProfiles();
      if (!state.selectionInitialized) {
        state.selectionInitialized = true;
        const first = Array.isArray(data.presets) ? data.presets[0] : null;
        if (first) state.selectedPresetId = first.id;
      }
      updatePresetSelection();
      updateRecipeUI();
      if (state.hasImage && state.selectedPresetId !== previousPreset) {
        markEdited({ immediate: true });
      }
      const presets = Array.isArray(data.presets) ? data.presets : [];
      const groups = Array.isArray(data.presetGroups) ? data.presetGroups : [];
      el.catalogCount.textContent = `${presets.length} 种风格 · ${groups.length} 组`;
      if (presets.length === 0) {
        setCatalogStatus("目录为空：没有可用的内置风格，仍可使用中性配方。");
      } else {
        setCatalogStatus("目录已从本机引擎资源载入。");
      }
    } catch (error) {
      state.catalog = null;
      state.catalogLoaded = false;
      el.catalogNav.replaceChildren();
      el.catalogCount.textContent = "目录不可用";
      setCatalogStatus(`目录加载失败：${displayError(error)}`, "error");
      el.catalogRetry.hidden = false;
      clearProfileSelects();
      updateRecipeUI();
    }
    updateActionStates();
  }

  function buildCatalog() {
    const presets = Array.isArray(state.catalog.presets) ? state.catalog.presets : [];
    const groups = Array.isArray(state.catalog.presetGroups) ? state.catalog.presetGroups : [];
    el.catalogNav.replaceChildren();
    if (presets.length === 0) {
      const empty = document.createElement("p");
      empty.className = "panel-status";
      empty.textContent = "目录为空：没有可用的内置风格。";
      el.catalogNav.append(empty);
      return;
    }

    const groupTitle = new Map();
    const groupOrder = new Map();
    groups.forEach((group, index) => {
      groupTitle.set(group.slug, group.title);
      groupOrder.set(group.slug, index);
    });

    const buckets = new Map();
    presets.forEach((preset, index) => {
      const slug = preset.groupSlug || "presets";
      if (!buckets.has(slug)) {
        buckets.set(slug, {
          slug,
          title: groupTitle.get(slug) || preset.group || slug,
          order: groupOrder.has(slug) ? groupOrder.get(slug) : 1000 + index,
          firstIndex: index,
          presets: [],
        });
      }
      buckets.get(slug).presets.push(preset);
    });

    const ordered = Array.from(buckets.values()).sort((a, b) => {
      if (a.order !== b.order) return a.order - b.order;
      return a.firstIndex - b.firstIndex;
    });

    for (const bucket of ordered) {
      const section = document.createElement("section");
      section.className = "preset-group";
      const heading = document.createElement("h3");
      heading.className = "preset-group-title";
      const title = document.createElement("span");
      title.textContent = bucket.title;
      const count = document.createElement("span");
      count.textContent = String(bucket.presets.length);
      heading.append(title, count);
      section.append(heading);
      for (const preset of bucket.presets) {
        section.append(createPresetButton(preset));
      }
      el.catalogNav.append(section);
    }
  }

  function createPresetButton(preset) {
    const button = document.createElement("button");
    button.type = "button";
    button.className = "preset-item";
    button.dataset.presetId = preset.id;
    if (preset.id === state.selectedPresetId) {
      button.setAttribute("aria-current", "true");
    }

    const check = document.createElement("span");
    check.className = "preset-check";
    check.setAttribute("aria-hidden", "true");
    check.textContent = "✓";

    const copy = document.createElement("span");
    copy.className = "preset-copy";
    const name = document.createElement("span");
    name.className = "preset-name";
    name.textContent = preset.name || preset.nameEn || preset.id;
    const spec = document.createElement("span");
    spec.className = "preset-spec";
    spec.textContent = preset.spec || "—";
    const description = document.createElement("span");
    description.className = "preset-desc";
    description.textContent = preset.description || preset.descriptionEn || "";
    copy.append(name, spec, description);
    button.append(check, copy);
    button.addEventListener("click", () => selectPreset(preset.id));
    return button;
  }

  function updatePresetSelection() {
    const buttons = el.catalogNav.querySelectorAll(".preset-item");
    buttons.forEach((button) => {
      if (button.dataset.presetId === state.selectedPresetId) {
        button.setAttribute("aria-current", "true");
      } else {
        button.removeAttribute("aria-current");
      }
    });
  }

  function fillSelect(select, items, emptyLabel) {
    select.replaceChildren();
    if (!Array.isArray(items) || items.length === 0) {
      const option = document.createElement("option");
      option.value = "";
      option.textContent = emptyLabel;
      option.disabled = true;
      option.selected = true;
      select.append(option);
      select.disabled = true;
      return;
    }
    select.disabled = false;
    for (const item of items) {
      const option = document.createElement("option");
      option.value = item.id;
      option.textContent = item.groupTitle ? `${item.name} · ${item.groupTitle}` : item.name;
      select.append(option);
    }
  }

  function clearProfileSelects() {
    fillSelect(el.filmSelect, [], "目录不可用");
    fillSelect(el.printSelect, [], "目录不可用");
  }

  function populateProfiles() {
    const films = Array.isArray(state.catalog.films) ? state.catalog.films : [];
    const prints = Array.isArray(state.catalog.prints) ? state.catalog.prints : [];
    fillSelect(el.filmSelect, films, "没有可用胶片");
    fillSelect(el.printSelect, prints, "没有可用相纸");
  }

  function syncSelect(select, value) {
    if (!value) return;
    const found = Array.from(select.options).some((option) => option.value === value);
    if (!found) {
      const option = document.createElement("option");
      option.value = value;
      option.textContent = value;
      select.append(option);
    }
    select.value = value;
  }

  // ---------------------------------------------------------------- recipe UI
  function updateRecipeUI() {
    const preset = getPreset();
    const strengthPercent = Math.round(state.strength * 100);
    const exposure = displayedExposure();
    const profiles = resolvedProfiles();
    const neutral = state.strength <= 0;

    el.strength.value = String(strengthPercent);
    el.strengthOutput.textContent = `${strengthPercent}%`;

    el.grain.value = String(Math.round(state.grainScale * 100));
    el.grainOutput.textContent = formatPercent(state.grainScale);
    el.halation.value = String(Math.round(state.halationScale * 100));
    el.halationOutput.textContent = formatPercent(state.halationScale);

    el.exposure.value = String(clamp(exposure, EV_MIN, EV_MAX));
    el.exposureOutput.textContent = formatEv(exposure);
    el.exposureReset.disabled = state.exposureOverride === null;
    if (state.exposureOverride !== null) {
      el.exposureNote.textContent = "手动覆盖：将显式发送 exposureEv";
    } else if (preset) {
      el.exposureNote.textContent =
        `跟随风格：${formatEv(authoredExposure())} EV × ${strengthPercent}%`;
    } else {
      el.exposureNote.textContent = "中性：0.00 EV";
    }

    syncSelect(el.filmSelect, profiles.film);
    syncSelect(el.printSelect, profiles.print);
    el.profileReset.disabled = state.filmOverride === null && state.printOverride === null;
    if (state.filmOverride !== null || state.printOverride !== null) {
      el.profileNote.textContent = "已手动覆盖；恢复跟随风格后重新由预设决定。";
    } else if (profiles.neutral) {
      el.profileNote.textContent = "强度 0% 或未选风格：使用明确的中性胶片与相纸。";
    } else {
      el.profileNote.textContent = "默认跟随当前预设；手动选择后发送覆写。";
    }

    if (neutral) {
      el.currentStyle.textContent = "中性配方 · 强度 0%";
      el.currentSpec.textContent =
        `${stockName(profiles.film)} · ${stockName(profiles.print)} · 中性`;
      el.recipeHint.textContent =
        "强度 0%：按中性配方（无风格叠加）渲染，风格参数不参与。";
    } else if (preset) {
      el.currentStyle.textContent = preset.name;
      el.currentSpec.textContent = preset.spec || "—";
      el.recipeHint.textContent =
        "预设中未手动调整的曝光按作者值 × 强度显示，且不发送 exposureEv 覆写；预览与导出共用同一份配方。";
    } else {
      el.currentStyle.textContent = "中性配方";
      el.currentSpec.textContent =
        `${stockName(profiles.film)} · ${stockName(profiles.print)} · 无预设叠加`;
      el.recipeHint.textContent = "未选择风格：使用明确中性配方渲染。";
    }

    if (state.hasImage) {
      el.effectImage.alt = `效果预览：${currentStyleLabel()}`;
    }
  }

  function updateImageMeta() {
    if (!state.hasImage || !state.image) {
      el.imageMeta.textContent = "未打开照片";
      return;
    }
    const image = state.image;
    el.imageMeta.textContent =
      `${image.fileName || "照片"} · ${formatDimensions(image.width, image.height)}` +
      ` · 预览 ${formatDimensions(image.previewWidth, image.previewHeight)}` +
      ` · ${String(image.format || "").toUpperCase()} · ${formatBytes(image.bytes)}`;
  }

  function updateActionStates() {
    const tokenReady = Boolean(state.token);
    const canOpen = tokenReady && !state.opening && !state.exporting;
    el.openButton.disabled = !canOpen;
    el.emptyOpen.disabled = !canOpen;
    el.refreshButton.disabled = !state.hasImage || state.opening || state.renderInFlight;
    el.exportButton.disabled =
      !state.hasImage ||
      !state.previewCurrent ||
      state.opening ||
      state.renderInFlight ||
      state.exporting;
    el.holdOriginal.disabled =
      !state.hasImage || state.opening || !el.effectImage.getAttribute("src");
    el.compareRange.disabled = !state.hasImage || state.opening;
    el.photoStage.setAttribute("aria-busy", state.renderInFlight ? "true" : "false");
  }

  function setControlsDisabled(disabled) {
    const controls = document.querySelectorAll(
      "#recipe-form input, #recipe-form select, #recipe-form button",
    );
    controls.forEach((control) => {
      control.disabled = disabled;
    });
  }

  // ---------------------------------------------------------------- split + hold
  function setSplit(value) {
    const split = clamp(Number(value) || 0, 0, 100);
    state.split = split;
    el.photoFrame.style.setProperty("--split", `${split}%`);
    el.compareRange.value = String(Math.round(split));
    el.splitOutput.textContent = `${Math.round(split)}%`;
  }

  function setHoldingOriginal(holding) {
    const active = Boolean(holding) && !el.holdOriginal.disabled;
    el.photoFrame.classList.toggle("show-original", active);
    el.holdOriginal.setAttribute("aria-pressed", active ? "true" : "false");
  }

  function layoutPhotoFrame() {
    if (!state.hasImage || !state.image || el.photoFrame.hidden) return;
    const style = window.getComputedStyle(el.photoStage);
    const padX = parseFloat(style.paddingLeft) + parseFloat(style.paddingRight);
    const padY = parseFloat(style.paddingTop) + parseFloat(style.paddingBottom);
    const availableWidth = Math.max(1, el.photoStage.clientWidth - padX);
    const availableHeight = Math.max(1, el.photoStage.clientHeight - padY);
    const imageWidth = Number(state.image.previewWidth) || Number(state.image.width) || 1;
    const imageHeight = Number(state.image.previewHeight) || Number(state.image.height) || 1;
    const aspect = imageWidth / imageHeight;
    let width = availableWidth;
    let height = width / aspect;
    if (height > availableHeight) {
      height = availableHeight;
      width = height * aspect;
    }
    el.photoFrame.style.width = `${Math.max(1, Math.floor(width))}px`;
    el.photoFrame.style.height = `${Math.max(1, Math.floor(height))}px`;
  }

  // ---------------------------------------------------------------- render loop
  function markEdited({ render = true, immediate = false } = {}) {
    state.editVersion += 1;
    state.previewCurrent = false;
    updateActionStates();
    if (render && state.hasImage) scheduleRender({ immediate });
  }

  function scheduleRender({ immediate = false } = {}) {
    if (!state.hasImage) return;
    if (debounceTimer !== null) {
      window.clearTimeout(debounceTimer);
      debounceTimer = null;
    }
    if (immediate) {
      requestRender();
      return;
    }
    debounceTimer = window.setTimeout(() => {
      debounceTimer = null;
      requestRender();
    }, DEBOUNCE_MS);
  }

  function requestRender() {
    if (!state.hasImage) return;
    state.renderQueued = true;
    if (state.renderInFlight) {
      updateActionStates();
      return;
    }
    void renderLoop();
  }

  async function renderLoop() {
    if (state.renderInFlight) return;
    state.renderInFlight = true;
    updateActionStates();
    try {
      while (state.renderQueued && state.hasImage) {
        state.renderQueued = false;
        await renderOnce();
      }
    } finally {
      state.renderInFlight = false;
      updateActionStates();
      if (state.renderQueued && state.hasImage) void renderLoop();
    }
  }

  async function renderOnce() {
    const sequence = ++state.renderSeq;
    const generation = ++state.generation;
    const version = state.editVersion;
    const session = state.sessionId;
    const recipe = buildRecipe();
    setRenderStatus("正在渲染当前配方…", "busy");
    updateActionStates();

    try {
      const response = await api("/api/preview", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ generation, recipe }),
      });
      const data = await responseJson(response);
      if (session !== state.sessionId) return;
      if (sequence !== state.renderSeq) return;
      if (!data || typeof data.previewUrl !== "string" || !data.previewUrl) {
        throw new Error("预览响应缺少图片地址。");
      }
      if (version !== state.editVersion) return;
      await preloadImage(data.previewUrl);
      if (session !== state.sessionId) return;
      if (sequence !== state.renderSeq || version !== state.editVersion) return;

      el.effectImage.src = data.previewUrl;
      state.lastRendered = {
        recipe,
        generation: Number(data.generation) || generation,
        width: Number(data.width) || 0,
        height: Number(data.height) || 0,
        ms: Number(data.ms) || 0,
        previewUrl: data.previewUrl,
      };
      state.previewCurrent = true;
      setRenderStatus(
        `已渲染 ${formatDimensions(data.width, data.height)} · ${Number(data.ms) || 0} ms · ${currentStyleLabel()}`,
        "ok",
      );
    } catch (error) {
      if (error && error.code === "stale_generation") {
        const current = Number(error.payload && error.payload.currentGeneration);
        if (Number.isFinite(current)) {
          state.generation = Math.max(state.generation, current);
        }
        if (
          session === state.sessionId &&
          sequence === state.renderSeq &&
          version === state.editVersion &&
          !state.renderQueued
        ) {
          setRenderStatus("旧预览已过期，未覆盖当前图像。", "muted");
        }
        return;
      }
      if (session !== state.sessionId || sequence !== state.renderSeq) return;
      if (version !== state.editVersion) return;
      state.previewCurrent = false;
      setRenderStatus(`渲染失败：${displayError(error)}`, "error");
    } finally {
      updateActionStates();
    }
  }

  // ---------------------------------------------------------------- open
  async function sniffFormat(file) {
    const head = new Uint8Array(await file.slice(0, 8).arrayBuffer());
    if (
      head.length >= 8 &&
      head[0] === 0x89 &&
      head[1] === 0x50 &&
      head[2] === 0x4e &&
      head[3] === 0x47 &&
      head[4] === 0x0d &&
      head[5] === 0x0a &&
      head[6] === 0x1a &&
      head[7] === 0x0a
    ) {
      return "png";
    }
    if (head.length >= 3 && head[0] === 0xff && head[1] === 0xd8 && head[2] === 0xff) {
      return "jpeg";
    }
    throw new Error("仅支持 JPEG 或 PNG 照片。");
  }

  function invalidatePhoto() {
    if (debounceTimer !== null) {
      window.clearTimeout(debounceTimer);
      debounceTimer = null;
    }
    state.hasImage = false;
    state.image = null;
    state.previewCurrent = false;
    state.lastRendered = null;
    state.renderQueued = false;
    el.originalImage.removeAttribute("src");
    el.effectImage.removeAttribute("src");
    el.photoFrame.hidden = true;
    el.emptyState.hidden = false;
    setSplit(50);
    updateImageMeta();
    updateActionStates();
  }

  async function openFile(file) {
    if (!file || state.opening || state.exporting || !state.token) return;
    state.opening = true;
    hideOpenError();
    updateActionStates();
    setRenderStatus("正在检查照片…", "busy");
    let session = null;

    try {
      if (file.size === 0) throw new Error("照片文件为空。");
      if (file.size > MAX_UPLOAD_BYTES) throw new Error("照片超过 100 MB 上限。");
      const format = await sniffFormat(file);

      session = ++state.sessionId;
      state.editVersion += 1;
      invalidatePhoto();
      setRenderStatus(`正在打开 ${file.name}…`, "busy");

      const bytes = await file.arrayBuffer();
      const response = await api("/api/open", {
        method: "POST",
        headers: {
          "Content-Type": "application/octet-stream",
          "X-Spektra-Format": format,
        },
        body: bytes,
      });
      const data = await responseJson(response);
      if (!data || data.ok !== true) throw new Error("打开响应缺少 ok 标记。");
      if (session !== state.sessionId) return;

      await preloadImage(data.previewUrl);
      if (session !== state.sessionId) return;

      state.hasImage = true;
      state.image = {
        fileName: file.name,
        width: Number(data.width) || 0,
        height: Number(data.height) || 0,
        previewWidth: Number(data.previewWidth) || Number(data.width) || 0,
        previewHeight: Number(data.previewHeight) || Number(data.height) || 0,
        format: data.format || format,
        bytes: Number(data.bytes) || file.size,
      };
      state.generation = Number(data.generation) || state.generation;
      state.previewCurrent = false;
      el.originalImage.src = data.previewUrl;
      el.effectImage.removeAttribute("src");
      el.photoFrame.hidden = false;
      el.emptyState.hidden = true;
      setSplit(50);
      updateImageMeta();
      updateRecipeUI();
      layoutPhotoFrame();
      setRenderStatus("已打开照片，正在渲染当前配方…", "busy");
      requestRender();
    } catch (error) {
      if (session !== null && session !== state.sessionId) return;
      if (session !== null) {
        invalidatePhoto();
      }
      const message = displayError(error);
      showOpenError(message);
      setRenderStatus(`打开失败：${message}`, "error");
    } finally {
      state.opening = false;
      updateActionStates();
    }
  }

  // ---------------------------------------------------------------- export
  function suggestedFilename() {
    const fileName = state.image && state.image.fileName ? state.image.fileName : "Spektrafilm";
    const base = fileName.replace(/\.[^.]+$/, "");
    const preset = getPreset();
    const style = state.strength > 0 && preset ? preset.id : "中性";
    return sanitizeFilename(`${base}_${style}`) || "Spektrafilm";
  }

  function updateFilenamePreview() {
    const format = selectedFormat();
    const extension = extensionFor(format);
    const cleaned = sanitizeFilename(el.filename.value);
    if (cleaned) {
      el.filenamePreview.textContent = `导出为 ${cleaned}.${extension}`;
    } else {
      el.filenamePreview.textContent =
        `导出为 Spektrafilm_日期_时间.${extension}（留空自动命名）`;
    }
  }

  function updateQualityRow() {
    const format = selectedFormat();
    el.qualityRow.hidden = format !== "jpeg";
  }

  function openExportDialog() {
    if (el.exportButton.disabled || !state.previewCurrent || !state.lastRendered) return;
    state.exportRecipe = JSON.parse(JSON.stringify(state.lastRendered.recipe));
    el.quality.value = String(DEFAULT_EXPORT_QUALITY);
    const jpegRadio = el.exportForm.querySelector('input[name="format"][value="jpeg"]');
    if (jpegRadio) jpegRadio.checked = true;
    el.filename.value = suggestedFilename();
    updateQualityRow();
    updateFilenamePreview();
    setExportStatus("导出会写入你在这台 Mac 上选择的下载位置。");
    el.exportClose.disabled = false;
    el.exportCancel.disabled = false;
    el.exportSubmit.disabled = false;
    el.exportSubmit.textContent = "导出";
    if (typeof el.exportDialog.showModal === "function") {
      el.exportDialog.showModal();
    } else {
      el.exportDialog.setAttribute("open", "");
    }
    el.filename.focus();
    el.filename.select();
  }

  function closeExportDialog() {
    if (state.exporting) return;
    if (typeof el.exportDialog.close === "function") {
      el.exportDialog.close("cancel");
    } else {
      el.exportDialog.removeAttribute("open");
      el.exportButton.focus();
    }
  }

  function parseDownloadFilename(disposition) {
    if (!disposition) return "";
    const star = /filename\*\s*=\s*UTF-8''([^;]+)/i.exec(disposition);
    if (star) {
      try {
        return decodeURIComponent(star[1].trim().replace(/^"|"$/g, ""));
      } catch (_error) {
        /* fall through to the ASCII filename */
      }
    }
    const quoted = /filename\s*=\s*"([^"]*)"/i.exec(disposition);
    if (quoted) return quoted[1].trim();
    const plain = /filename\s*=\s*([^;]+)/i.exec(disposition);
    return plain ? plain[1].trim().replace(/^"|"$/g, "") : "";
  }

  function downloadBlob(blob, filename) {
    const url = URL.createObjectURL(blob);
    const link = document.createElement("a");
    link.href = url;
    link.download = filename;
    link.rel = "noopener";
    link.style.display = "none";
    document.body.append(link);
    link.click();
    link.remove();
    window.setTimeout(() => URL.revokeObjectURL(url), 1200);
  }

  async function runExport() {
    if (state.exporting || !state.exportRecipe) return;
    const format = selectedFormat();
    let quality = DEFAULT_EXPORT_QUALITY;
    if (format === "jpeg") {
      const parsed = Number.parseInt(el.quality.value, 10);
      if (!Number.isInteger(parsed) || parsed < 1 || parsed > 100) {
        setExportStatus("JPEG 质量必须是 1–100 的整数。", "error");
        el.quality.focus();
        return;
      }
      quality = parsed;
    }
    const cleanName = sanitizeFilename(el.filename.value);
    state.exporting = true;
    el.exportClose.disabled = true;
    el.exportCancel.disabled = true;
    el.exportSubmit.disabled = true;
    el.exportSubmit.textContent = "正在导出…";
    el.exportDialog.setAttribute("aria-busy", "true");
    setExportStatus("正在调用本机引擎导出…大图可能需要一些时间。", "busy");
    updateActionStates();

    try {
      const response = await api("/api/export", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          recipe: state.exportRecipe,
          format,
          quality,
          filename: cleanName,
        }),
      });
      const blob = await response.blob();
      if (!blob.size) throw new Error("导出文件为空。");
      const fallbackName = `${cleanName || `Spektrafilm_${timestampLabel()}`}.${extensionFor(format)}`;
      const filename =
        parseDownloadFilename(response.headers.get("Content-Disposition") || "") || fallbackName;
      const width = response.headers.get("X-Spektra-Width") || "?";
      const height = response.headers.get("X-Spektra-Height") || "?";
      const ms = response.headers.get("X-Spektra-Ms") || "?";
      downloadBlob(blob, filename);
      setExportStatus(`已导出 ${filename} · ${width} × ${height} · ${ms} ms`, "ok");
      el.exportSubmit.textContent = "再次导出";
    } catch (error) {
      setExportStatus(`导出失败：${displayError(error)} 可修改选项后重试。`, "error");
      el.exportSubmit.textContent = "重试导出";
    } finally {
      state.exporting = false;
      el.exportClose.disabled = false;
      el.exportCancel.disabled = false;
      el.exportSubmit.disabled = false;
      el.exportDialog.removeAttribute("aria-busy");
      updateActionStates();
    }
  }

  // ---------------------------------------------------------------- actions
  function selectPreset(id) {
    const preset = getPresetById(id);
    if (!preset) return;
    state.selectedPresetId = id;
    if (state.strength <= 0) state.strength = 1;
    updatePresetSelection();
    updateRecipeUI();
    markEdited({ immediate: true });
  }

  function resetRecipe() {
    state.selectedPresetId = null;
    state.strength = 0;
    state.grainScale = 1;
    state.halationScale = 1;
    state.exposureOverride = null;
    state.filmOverride = null;
    state.printOverride = null;
    updatePresetSelection();
    updateRecipeUI();
    markEdited({ immediate: true });
  }

  function bindEvents() {
    el.openButton.addEventListener("click", () => el.fileInput.click());
    el.emptyOpen.addEventListener("click", () => el.fileInput.click());
    el.fileInput.addEventListener("change", () => {
      const file = el.fileInput.files && el.fileInput.files[0];
      el.fileInput.value = "";
      if (file) void openFile(file);
    });

    el.refreshButton.addEventListener("click", () => {
      if (!state.hasImage) return;
      scheduleRender({ immediate: true });
    });

    el.strength.addEventListener("input", () => {
      state.strength = clamp(Number(el.strength.value) / 100, 0, 1);
      updateRecipeUI();
      markEdited();
    });

    el.grain.addEventListener("input", () => {
      state.grainScale = clamp(Number(el.grain.value) / 100, 0, 4);
      updateRecipeUI();
      markEdited();
    });

    el.halation.addEventListener("input", () => {
      state.halationScale = clamp(Number(el.halation.value) / 100, 0, 4);
      updateRecipeUI();
      markEdited();
    });

    el.exposure.addEventListener("input", () => {
      state.exposureOverride = clamp(Number(el.exposure.value), EV_MIN, EV_MAX);
      updateRecipeUI();
      markEdited();
    });

    el.exposureReset.addEventListener("click", () => {
      state.exposureOverride = null;
      updateRecipeUI();
      markEdited({ immediate: true });
    });

    el.filmSelect.addEventListener("change", () => {
      if (!el.filmSelect.value) return;
      state.filmOverride = el.filmSelect.value;
      updateRecipeUI();
      markEdited({ immediate: true });
    });

    el.printSelect.addEventListener("change", () => {
      if (!el.printSelect.value) return;
      state.printOverride = el.printSelect.value;
      updateRecipeUI();
      markEdited({ immediate: true });
    });

    el.profileReset.addEventListener("click", () => {
      state.filmOverride = null;
      state.printOverride = null;
      updateRecipeUI();
      markEdited({ immediate: true });
    });

    el.resetButton.addEventListener("click", resetRecipe);
    el.catalogRetry.addEventListener("click", () => void loadCatalog());

    el.compareRange.addEventListener("input", () => setSplit(el.compareRange.value));

    el.holdOriginal.addEventListener("pointerdown", (event) => {
      if (event.button !== 0) return;
      setHoldingOriginal(true);
      try {
        el.holdOriginal.setPointerCapture(event.pointerId);
      } catch (_error) {
        /* Pointer capture is an enhancement, not a requirement. */
      }
    });
    el.holdOriginal.addEventListener("pointerup", () => setHoldingOriginal(false));
    el.holdOriginal.addEventListener("pointercancel", () => setHoldingOriginal(false));
    el.holdOriginal.addEventListener("lostpointercapture", () => setHoldingOriginal(false));
    window.addEventListener("pointerup", () => setHoldingOriginal(false));

    el.holdOriginal.addEventListener("keydown", (event) => {
      if (event.key === " " || event.key === "Enter") {
        event.preventDefault();
        setHoldingOriginal(true);
      }
    });
    el.holdOriginal.addEventListener("keyup", (event) => {
      if (event.key === " " || event.key === "Enter") {
        event.preventDefault();
        setHoldingOriginal(false);
      }
    });
    el.holdOriginal.addEventListener("blur", () => setHoldingOriginal(false));

    el.exportButton.addEventListener("click", openExportDialog);
    el.exportClose.addEventListener("click", closeExportDialog);
    el.exportCancel.addEventListener("click", closeExportDialog);
    el.exportDialog.addEventListener("cancel", (event) => {
      if (state.exporting) {
        event.preventDefault();
        setExportStatus("导出进行中，请等待完成。", "busy");
      }
    });
    el.exportDialog.addEventListener("close", () => {
      state.exportRecipe = null;
      if (!state.exporting) el.exportButton.focus();
    });
    el.exportForm.addEventListener("submit", (event) => {
      event.preventDefault();
      void runExport();
    });
    el.exportForm.querySelectorAll('input[name="format"]').forEach((radio) => {
      radio.addEventListener("change", () => {
        updateQualityRow();
        updateFilenamePreview();
        setExportStatus("导出会写入你在这台 Mac 上选择的下载位置。");
      });
    });
    el.filename.addEventListener("input", updateFilenamePreview);

    el.photoStage.addEventListener("dragenter", (event) => {
      if (!event.dataTransfer || !Array.from(event.dataTransfer.types).includes("Files")) {
        return;
      }
      event.preventDefault();
      el.photoStage.classList.add("is-dragover");
    });
    el.photoStage.addEventListener("dragover", (event) => {
      if (!event.dataTransfer || !Array.from(event.dataTransfer.types).includes("Files")) {
        return;
      }
      event.preventDefault();
      event.dataTransfer.dropEffect = "copy";
      el.photoStage.classList.add("is-dragover");
    });
    el.photoStage.addEventListener("dragleave", () => {
      el.photoStage.classList.remove("is-dragover");
    });
    el.photoStage.addEventListener("drop", (event) => {
      event.preventDefault();
      el.photoStage.classList.remove("is-dragover");
      const file =
        event.dataTransfer && event.dataTransfer.files && event.dataTransfer.files[0];
      if (file) void openFile(file);
    });

    window.addEventListener("resize", layoutPhotoFrame);
    if (typeof ResizeObserver === "function") {
      const observer = new ResizeObserver(() => layoutPhotoFrame());
      observer.observe(el.photoStage);
    }
  }

  function initialize() {
    state.token = readToken();
    bindEvents();
    updateActionStates();
    setSplit(50);
    updateRecipeUI();

    if (!state.token) {
      const message =
        "缺少访问令牌。请关闭此页，通过「Start Spektrafilm.command」或 run.py 重新打开本地编辑器。";
      setCatalogStatus(message, "error");
      setRenderStatus(message, "error");
      el.catalogCount.textContent = "不可用";
      el.catalogRetry.hidden = true;
      el.emptyOpen.disabled = true;
      showOpenError(message);
      setControlsDisabled(true);
      return;
    }

    void loadCatalog();
  }

  initialize();
})();
