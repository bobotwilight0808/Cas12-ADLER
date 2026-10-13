const MAX_FILE_SIZE = 15 * 1024 * 1024;
const ACCEPTED_TYPES = new Set(["image/jpeg", "image/png", "image/webp"]);

const elements = {
  fileInput: document.querySelector("#file-input"),
  dropZone: document.querySelector("#drop-zone"),
  uploadEmpty: document.querySelector("#upload-empty"),
  previewMedia: document.querySelector("#preview-media"),
  previewImage: document.querySelector("#preview-image"),
  overlay: document.querySelector("#detection-overlay"),
  fileDetails: document.querySelector("#file-details"),
  fileName: document.querySelector("#file-name"),
  fileMeta: document.querySelector("#file-meta"),
  uploadError: document.querySelector("#upload-error"),
  analyzeButton: document.querySelector("#analyze-button"),
  resetButton: document.querySelector("#reset-button"),
  resultEmpty: document.querySelector("#result-empty"),
  resultLoading: document.querySelector("#result-loading"),
  resultError: document.querySelector("#result-error"),
  resultErrorMessage: document.querySelector("#result-error-message"),
  resultContent: document.querySelector("#result-content"),
  resultSummary: document.querySelector("#result-summary"),
  confidenceNotice: document.querySelector("#confidence-notice"),
  stripDetails: document.querySelector("#strip-details"),
  resultTimestamp: document.querySelector("#result-timestamp"),
};

let selectedFile = null;
let previewUrl = null;

elements.dropZone.addEventListener("click", () => {
  if (!selectedFile) elements.fileInput.click();
});

elements.dropZone.addEventListener("keydown", (event) => {
  if (!selectedFile && (event.key === "Enter" || event.key === " ")) {
    event.preventDefault();
    elements.fileInput.click();
  }
});

elements.fileInput.addEventListener("change", () => {
  const [file] = elements.fileInput.files;
  if (file) selectFile(file);
});

for (const eventName of ["dragenter", "dragover"]) {
  elements.dropZone.addEventListener(eventName, (event) => {
    event.preventDefault();
    if (!selectedFile) elements.dropZone.classList.add("is-dragging");
  });
}

for (const eventName of ["dragleave", "drop"]) {
  elements.dropZone.addEventListener(eventName, (event) => {
    event.preventDefault();
    elements.dropZone.classList.remove("is-dragging");
  });
}

elements.dropZone.addEventListener("drop", (event) => {
  if (selectedFile) return;
  const [file] = event.dataTransfer.files;
  if (file) selectFile(file);
});

elements.resetButton.addEventListener("click", resetSample);
elements.analyzeButton.addEventListener("click", analyzeSample);

document.querySelectorAll('input[name="strip-type"]').forEach((input) => {
  input.addEventListener("change", () => {
    clearOverlay();
    showResultState("empty");
  });
});

function selectFile(file) {
  clearUploadError();
  if (!ACCEPTED_TYPES.has(file.type)) {
    showUploadError("Choose a JPG, PNG, or WebP image.");
    return;
  }
  if (file.size > MAX_FILE_SIZE) {
    showUploadError("Image size must not exceed 15 MB.");
    return;
  }

  selectedFile = file;
  if (previewUrl) URL.revokeObjectURL(previewUrl);
  previewUrl = URL.createObjectURL(file);
  elements.previewImage.src = previewUrl;
  elements.fileName.textContent = file.name;
  elements.fileMeta.textContent = formatFileSize(file.size);
  elements.uploadEmpty.hidden = true;
  elements.previewMedia.hidden = false;
  elements.fileDetails.hidden = false;
  elements.resetButton.hidden = false;
  elements.dropZone.classList.add("has-file");
  clearOverlay();
  showResultState("empty");
}

function resetSample() {
  selectedFile = null;
  elements.fileInput.value = "";
  if (previewUrl) URL.revokeObjectURL(previewUrl);
  previewUrl = null;
  elements.previewImage.removeAttribute("src");
  elements.uploadEmpty.hidden = false;
  elements.previewMedia.hidden = true;
  elements.fileDetails.hidden = true;
  elements.resetButton.hidden = true;
  elements.dropZone.classList.remove("has-file");
  clearUploadError();
  clearOverlay();
  showResultState("empty");
}

async function analyzeSample() {
  if (!selectedFile) return;
  const stripType = document.querySelector('input[name="strip-type"]:checked').value;
  const formData = new FormData();
  formData.append("image", selectedFile);
  formData.append("strip_type", stripType);

  elements.analyzeButton.disabled = true;
  showResultState("loading");
  clearOverlay();

  try {
    const response = await fetch("/api/analyze", { method: "POST", body: formData });
    const payload = await response.json().catch(() => ({}));
    if (!response.ok) throw new Error(payload.error || "The service is unavailable. Try again later.");
    renderResult(payload);
    renderOverlay(payload.strips || []);
  } catch (error) {
    elements.resultErrorMessage.textContent = error.message;
    showResultState("error");
  } finally {
    elements.analyzeButton.disabled = false;
  }
}

function renderResult(data) {
  const interpretation = interpretResult(data);
  const confidenceLabel = data.confidence === "high" ? "High" : "Low";
  const stripCount = Number(data.summary?.strip_count || 0);
  elements.resultSummary.className = `result-summary ${interpretation.className}`;
  elements.resultSummary.innerHTML = `
    <p class="result-kicker">${escapeHtml(interpretation.kicker)}</p>
    <div class="result-title-row">
      <h3>${escapeHtml(interpretation.title)}</h3>
      <span class="result-badge">
        <svg aria-hidden="true"><use href="#${interpretation.icon}"></use></svg>
        ${escapeHtml(interpretation.badge)}
      </span>
    </div>
    <div class="result-meta">
      <span>Confidence <strong>${confidenceLabel}</strong></span>
      <span>Strips <strong>${stripCount}</strong></span>
      <span>Mode <strong>${data.strip_type === "single" ? "Single" : "Dual"}</strong></span>
    </div>`;

  elements.confidenceNotice.hidden = data.confidence !== "low" || data.readability !== "ok";
  elements.stripDetails.innerHTML = (data.strips || []).map((strip) => renderStrip(strip, data.strip_type)).join("");
  elements.resultTimestamp.textContent = new Intl.DateTimeFormat("en-GB", {
    hour: "2-digit", minute: "2-digit", second: "2-digit",
  }).format(new Date());
  showResultState("content");
}

function interpretResult(data) {
  if (data.readability !== "ok") {
    return { title: "Image unreadable", kicker: "Analysis incomplete", badge: "Retake photo", className: "is-warning", icon: "icon-alert-circle" };
  }
  if (data.validity === "invalid_no_lines") {
    return { title: "No visible lines", kicker: "Invalid test", badge: "Retest", className: "is-warning", icon: "icon-alert-circle" };
  }
  if (data.strip_type === "single") {
    const positive = data.result?.single === "positive";
    return positive
      ? { title: "Positive", kicker: "Single-target result", badge: "T line visible", className: "is-positive", icon: "icon-check-circle" }
      : { title: "Negative", kicker: "Single-target result", badge: "Complete", className: "is-negative", icon: "icon-check-circle" };
  }

  const t1 = data.result?.targets?.T1 === "positive";
  const t2 = data.result?.targets?.T2 === "positive";
  if (t1 && t2) return { title: "T1 / T2 positive", kicker: "Dual-target result", badge: "Complete", className: "is-positive", icon: "icon-check-circle" };
  if (t1) return { title: "T1 positive", kicker: "Dual-target result", badge: "Complete", className: "is-positive", icon: "icon-check-circle" };
  if (t2) return { title: "T2 positive", kicker: "Dual-target result", badge: "Complete", className: "is-positive", icon: "icon-check-circle" };
  return { title: "Negative", kicker: "Dual-target result", badge: "Complete", className: "is-negative", icon: "icon-check-circle" };
}

function renderStrip(strip, stripType) {
  const order = stripType === "single" ? ["T", "C"] : ["T2", "T1", "C"];
  const validity = strip.validity === "valid" ? "Valid" : "Invalid";
  const lines = order.map((lineName) => renderLine(lineName, strip.line_scores?.[lineName])).join("");
  return `
    <article class="strip-card">
      <div class="strip-card-header">
        <strong>Strip ${Number(strip.index) + 1}</strong>
        <span>${validity} · ${strip.confidence === "high" ? "High confidence" : "Low confidence"}</span>
      </div>
      ${lines}
    </article>`;
}

function renderLine(name, reading) {
  if (!reading) return "";
  const score = Number(reading.score || 0);
  const threshold = Math.max(Number(reading.threshold || 1), 0.01);
  const width = Math.max(2, Math.min(100, (Math.max(score, 0) / (threshold * 1.6)) * 100));
  const present = Boolean(reading.present);
  return `
    <div class="line-reading">
      <span class="line-name">${escapeHtml(name)}</span>
      <span class="score-track" title="Score ${score.toFixed(1)}, threshold ${threshold.toFixed(1)}">
        <span class="score-fill ${present ? "is-present" : ""}" style="width:${width}%"></span>
        <span class="score-threshold"></span>
      </span>
      <span class="line-value">
        <span class="line-state ${present ? "is-present" : ""}">${present ? "Visible" : "Not visible"}</span>
        <span class="score-number">${score.toFixed(1)}</span>
      </span>
    </div>`;
}

function renderOverlay(strips) {
  const width = elements.previewImage.naturalWidth;
  const height = elements.previewImage.naturalHeight;
  if (!width || !height || !strips.length) return;
  elements.overlay.setAttribute("viewBox", `0 0 ${width} ${height}`);
  elements.overlay.setAttribute("preserveAspectRatio", "xMidYMid meet");

  const namespace = "http://www.w3.org/2000/svg";
  strips.forEach((strip, index) => {
    const corners = strip.detection?.corners;
    if (!Array.isArray(corners) || corners.length !== 4) return;
    const polygon = document.createElementNS(namespace, "polygon");
    polygon.setAttribute("points", corners.map((point) => point.join(",")).join(" "));
    polygon.setAttribute("class", "detected-polygon");
    elements.overlay.appendChild(polygon);

    const marker = document.createElementNS(namespace, "circle");
    marker.setAttribute("cx", corners[0][0]);
    marker.setAttribute("cy", corners[0][1]);
    marker.setAttribute("r", Math.max(width, height) * 0.008);
    marker.setAttribute("class", "detected-label");
    elements.overlay.appendChild(marker);
  });
}

function clearOverlay() {
  elements.overlay.replaceChildren();
  elements.overlay.removeAttribute("viewBox");
  elements.resultTimestamp.textContent = "";
}

function showResultState(state) {
  elements.resultEmpty.hidden = state !== "empty";
  elements.resultLoading.hidden = state !== "loading";
  elements.resultError.hidden = state !== "error";
  elements.resultContent.hidden = state !== "content";
  if (state !== "content") elements.resultTimestamp.textContent = "";
}

function showUploadError(message) {
  elements.uploadError.textContent = message;
  elements.uploadError.hidden = false;
}

function clearUploadError() {
  elements.uploadError.textContent = "";
  elements.uploadError.hidden = true;
}

function formatFileSize(bytes) {
  if (bytes < 1024 * 1024) return `${Math.max(1, Math.round(bytes / 1024))} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

function escapeHtml(value) {
  return String(value).replace(/[&<>'"]/g, (character) => ({
    "&": "&amp;", "<": "&lt;", ">": "&gt;", "'": "&#39;", '"': "&quot;",
  })[character]);
}
