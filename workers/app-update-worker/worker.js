const DEFAULT_GITHUB_REPO = "omnimind-ai/OpenOmniBot";
const DEFAULT_CNB_REPO = "o.a/OpenOmniBot";
const DEFAULT_EDITIONS = ["omniinfer", "standard"];
const JSON_HEADERS = {
  "content-type": "application/json; charset=utf-8",
  "cache-control": "no-store",
  "access-control-allow-origin": "*",
};

export default {
  async fetch(request, env) {
    if (!env.APP_UPDATE_REGISTRY) {
      return json({ ok: false, error: "APP_UPDATE_REGISTRY Durable Object binding is missing" }, 500);
    }

    const id = env.APP_UPDATE_REGISTRY.idFromName("global");
    return env.APP_UPDATE_REGISTRY.get(id).fetch(request);
  },
};

export class AppUpdateRegistry {
  constructor(state, env) {
    this.state = state;
    this.env = env;
  }

  async fetch(request) {
    const url = new URL(request.url);
    const pathname = normalizePath(url.pathname);

    if (request.method === "OPTIONS") {
      return new Response(null, {
        status: 204,
        headers: {
          ...JSON_HEADERS,
          "access-control-allow-methods": "GET,POST,DELETE,OPTIONS",
          "access-control-allow-headers": "authorization,content-type,x-update-token",
        },
      });
    }

    try {
      if (request.method === "GET" && pathname === "/") {
        return json({
          ok: true,
          service: "omnibot-app-update-worker",
          routes: ["/updates", "/admin/releases", "/admin/releases/:tag", "/admin/stats"],
        });
      }

      if (request.method === "GET" && pathname === "/updates") {
        return this.handleUpdateCheck(url);
      }

      if (pathname === "/admin/releases" && request.method === "GET") {
        this.requireAdmin(request);
        return this.handleListReleases();
      }

      if (pathname === "/admin/releases" && request.method === "POST") {
        this.requireAdmin(request);
        return this.handleUpsertRelease(request);
      }

      if (pathname === "/admin/releases" && request.method === "DELETE") {
        this.requireAdmin(request);
        return this.handleDeleteRelease(url.searchParams.get("tag"));
      }

      if (pathname.startsWith("/admin/releases/") && request.method === "DELETE") {
        this.requireAdmin(request);
        return this.handleDeleteRelease(decodeURIComponent(pathname.slice("/admin/releases/".length)));
      }

      if (pathname === "/admin/stats" && request.method === "GET") {
        this.requireAdmin(request);
        return this.handleStats();
      }

      return json({ ok: false, error: "Not found" }, 404);
    } catch (error) {
      const status = Number.isInteger(error.status) ? error.status : 500;
      return json({ ok: false, error: error.message || "Internal error" }, status);
    }
  }

  async handleUpdateCheck(url) {
    const currentVersion = normalizeVersion(
      url.searchParams.get("currentVersion") ||
        url.searchParams.get("current_version") ||
        url.searchParams.get("version") ||
        "",
    );
    const includeBeta = parseBoolean(url.searchParams.get("includeBeta") || url.searchParams.get("include_beta"));
    const edition = normalizeEdition(url.searchParams.get("edition"));
    const source = normalizeSource(url.searchParams.get("source") || this.env.DEFAULT_SOURCE || "cnb");
    const checkedAt = Date.now();

    await this.recordCheck({
      currentVersion: currentVersion || "unknown",
      edition,
      source,
      checkedAt,
    });

    const releases = await this.loadReleases();
    const selected = selectLatestRelease(releases, includeBeta);
    if (!selected) {
      return json(emptyUpdateResponse({ currentVersion, checkedAt, edition, source }));
    }

    const asset = selectPreferredApkAsset(selected.assets, edition);
    const latestVersion = selected.version;
    const hasUpdate = Boolean(asset) && compareVersions(latestVersion, currentVersion) > 0;

    return json({
      ok: true,
      currentVersion,
      latestVersion,
      hasUpdate,
      checkedAt,
      publishedAt: selected.publishedAt || 0,
      tag: selected.tag,
      track: selected.track,
      releaseUrl: selected.releaseUrl || "",
      releaseNotes: selected.releaseNotes || "",
      apkName: asset?.name || "",
      apkDownloadUrl: asset ? assetDownloadUrl(asset, source) : "",
      edition,
      source,
      assets: selected.assets.map(publicAsset),
    });
  }

  async handleListReleases() {
    const releases = await this.loadReleases({ includeDrafts: true });
    return json({ ok: true, releases });
  }

  async handleUpsertRelease(request) {
    const body = await readJson(request);
    const release = normalizeRelease(body, this.env);

    await this.state.storage.transaction(async (txn) => {
      const index = (await txn.get("tag_index")) || [];
      if (!index.includes(release.tag)) {
        index.push(release.tag);
      }
      await txn.put("tag_index", index);
      await txn.put(releaseKey(release.tag), release);
    });

    return json({ ok: true, release });
  }

  async handleDeleteRelease(rawTag) {
    const tag = normalizeTag(rawTag);
    if (!tag) {
      throw httpError(400, "tag is required");
    }

    let deleted = false;
    await this.state.storage.transaction(async (txn) => {
      const key = releaseKey(tag);
      const existing = await txn.get(key);
      deleted = Boolean(existing);
      await txn.delete(key);

      const index = (await txn.get("tag_index")) || [];
      await txn.put(
        "tag_index",
        index.filter((item) => item !== tag),
      );

      const stats = (await txn.get("stats")) || defaultStats();
      stats.deletedTags = (stats.deletedTags || 0) + (deleted ? 1 : 0);
      stats.lastDeletedAt = deleted ? Date.now() : stats.lastDeletedAt || 0;
      await txn.put("stats", stats);
    });

    return json({ ok: true, tag, deleted });
  }

  async handleStats() {
    const stats = (await this.state.storage.get("stats")) || defaultStats();
    return json({ ok: true, stats });
  }

  async loadReleases({ includeDrafts = false } = {}) {
    const entries = await this.state.storage.list({ prefix: "release:" });
    return Array.from(entries.values())
      .filter((release) => includeDrafts || (!release.draft && release.track !== "unsupported"))
      .sort((left, right) => {
        const versionOrder = compareVersions(right.version, left.version);
        if (versionOrder !== 0) return versionOrder;
        return (right.publishedAt || 0) - (left.publishedAt || 0);
      });
  }

  async recordCheck({ currentVersion, edition, source, checkedAt }) {
    const day = new Date(checkedAt).toISOString().slice(0, 10);
    await this.state.storage.transaction(async (txn) => {
      const stats = (await txn.get("stats")) || defaultStats();
      stats.totalChecks += 1;
      stats.lastCheckedAt = checkedAt;
      increment(stats.byDay, day);
      increment(stats.byVersion, currentVersion);
      increment(stats.byEdition, edition);
      increment(stats.bySource, source);
      await txn.put("stats", stats);
    });
  }

  requireAdmin(request) {
    const expected = this.env.ADMIN_TOKEN || this.env.APP_UPDATE_WORKER_TOKEN;
    if (!expected) {
      throw httpError(500, "ADMIN_TOKEN is not configured");
    }

    const auth = request.headers.get("authorization") || "";
    const bearerToken = auth.replace(/^Bearer\s+/i, "").trim();
    const headerToken = (request.headers.get("x-update-token") || "").trim();
    if (bearerToken !== expected && headerToken !== expected) {
      throw httpError(401, "Unauthorized");
    }
  }
}

function normalizeRelease(input, env) {
  if (!input || typeof input !== "object") {
    throw httpError(400, "JSON object body is required");
  }

  const tag = normalizeTag(input.tag || input.tagName || input.tag_name);
  if (!tag) {
    throw httpError(400, "tag is required");
  }

  const version = normalizeVersion(input.version || input.latestVersion || tag);
  const track = normalizeTrack(input.track) || classifyReleaseTrack(version, input.prerelease);
  const publishedAt = normalizeTimestamp(input.publishedAt || input.published_at || Date.now());
  const assets = normalizeAssets(input.assets, tag, env);

  return {
    tag,
    version,
    track,
    draft: Boolean(input.draft),
    prerelease: Boolean(input.prerelease),
    publishedAt,
    releaseUrl: stringValue(input.releaseUrl || input.htmlUrl || input.html_url || input.url),
    releaseNotes: stringValue(input.releaseNotes || input.notes || input.body),
    assets,
    updatedAt: Date.now(),
  };
}

function normalizeAssets(rawAssets, tag, env) {
  const assets = Array.isArray(rawAssets)
    ? rawAssets.map((asset) => normalizeAsset(asset)).filter(Boolean)
    : [];

  if (assets.length > 0) {
    return assets;
  }

  return DEFAULT_EDITIONS.map((edition) => buildDefaultAsset(tag, edition, env));
}

function normalizeAsset(asset) {
  if (!asset || typeof asset !== "object") return null;
  const name = stringValue(asset.name || asset.fileName || asset.filename);
  if (!name.toLowerCase().endsWith(".apk")) return null;
  return {
    name,
    downloadUrl: stringValue(asset.downloadUrl || asset.browser_download_url),
    githubDownloadUrl: stringValue(asset.githubDownloadUrl || asset.github_download_url || asset.browser_download_url),
    cnbDownloadUrl: stringValue(asset.cnbDownloadUrl || asset.cnb_download_url),
  };
}

function buildDefaultAsset(tag, edition, env) {
  const name = `OpenOmniBot-${tag}-${edition}.apk`;
  const githubRepo = env.GITHUB_REPO || DEFAULT_GITHUB_REPO;
  const cnbRepo = env.CNB_REPO || DEFAULT_CNB_REPO;
  return {
    name,
    githubDownloadUrl: `https://github.com/${githubRepo}/releases/download/${encodeURIComponent(tag)}/${encodeURIComponent(name)}`,
    cnbDownloadUrl: `https://cnb.cool/${cnbRepo}/-/releases/download/${encodeURIComponent(tag)}/${encodeURIComponent(name)}`,
  };
}

function selectLatestRelease(releases, includeBeta) {
  return releases
    .filter((release) => release.track === "stable" || (includeBeta && release.track === "beta"))
    .reduce((selected, release) => {
      if (!selected) return release;
      const versionOrder = compareVersions(release.version, selected.version);
      if (versionOrder > 0) return release;
      if (versionOrder === 0 && (release.publishedAt || 0) > (selected.publishedAt || 0)) {
        return release;
      }
      return selected;
    }, null);
}

function selectPreferredApkAsset(assets, edition) {
  const apkAssets = (assets || []).filter((asset) => asset.name.toLowerCase().endsWith(".apk"));
  const editionAsset = apkAssets.find((asset) => isEditionApkAsset(asset.name, edition));
  if (editionAsset) return editionAsset;
  if (apkAssets.some((asset) => isKnownEditionApkAsset(asset.name))) return null;
  return apkAssets.find((asset) => /^OpenOmniBot-v/i.test(asset.name)) || apkAssets[0] || null;
}

function assetDownloadUrl(asset, source) {
  if (source === "github") {
    return asset.githubDownloadUrl || asset.downloadUrl || asset.cnbDownloadUrl || "";
  }
  return asset.cnbDownloadUrl || asset.downloadUrl || asset.githubDownloadUrl || "";
}

function publicAsset(asset) {
  return {
    name: asset.name,
    downloadUrl: asset.downloadUrl || "",
    githubDownloadUrl: asset.githubDownloadUrl || "",
    cnbDownloadUrl: asset.cnbDownloadUrl || "",
  };
}

function normalizePath(pathname) {
  if (!pathname || pathname === "/") return "/";
  return pathname.replace(/\/+$/, "");
}

function normalizeTag(raw) {
  return stringValue(raw).replace(/^refs\/tags\//, "").trim();
}

function normalizeVersion(raw) {
  return stringValue(raw)
    .replace(/^refs\/tags\//, "")
    .replace(/^[vV]/, "")
    .split("+")[0]
    .trim();
}

function normalizeTrack(raw) {
  const value = stringValue(raw).toLowerCase();
  if (value === "stable") return "stable";
  if (value === "beta" || value === "prerelease" || value === "pre-release") return "beta";
  return "";
}

function classifyReleaseTrack(version, prerelease) {
  if (prerelease) return "beta";
  const parts = normalizeVersion(version).split(".");
  if (parts.length === 3 && parts.every(isDigits)) return "stable";
  if (parts.length === 4 && parts.every(isDigits)) return "beta";
  return "unsupported";
}

function compareVersions(leftRaw, rightRaw) {
  const left = normalizeVersion(leftRaw);
  const right = normalizeVersion(rightRaw);
  if (left === right) return 0;

  const leftParts = numericParts(left);
  const rightParts = numericParts(right);
  if (leftParts && rightParts) {
    const length = Math.max(leftParts.length, rightParts.length);
    for (let index = 0; index < length; index += 1) {
      const leftValue = leftParts[index] || 0;
      const rightValue = rightParts[index] || 0;
      if (leftValue !== rightValue) {
        return leftValue > rightValue ? 1 : -1;
      }
    }
    return 0;
  }

  return left.localeCompare(right);
}

function numericParts(version) {
  if (!version) return null;
  const parts = version.split(".");
  if (!parts.every(isDigits)) return null;
  return parts.map((part) => Number(part));
}

function isDigits(value) {
  return /^\d+$/.test(value);
}

function isEditionApkAsset(name, edition) {
  return name.toLowerCase().endsWith(`-${edition}.apk`);
}

function isKnownEditionApkAsset(name) {
  const normalized = name.toLowerCase();
  return normalized.endsWith("-standard.apk") || normalized.endsWith("-omniinfer.apk");
}

function normalizeEdition(raw) {
  const value = stringValue(raw).toLowerCase();
  return value === "standard" ? "standard" : "omniinfer";
}

function normalizeSource(raw) {
  return stringValue(raw).toLowerCase() === "github" ? "github" : "cnb";
}

function parseBoolean(raw) {
  const value = stringValue(raw).toLowerCase();
  return value === "1" || value === "true" || value === "yes";
}

function normalizeTimestamp(raw) {
  if (typeof raw === "number" && Number.isFinite(raw)) {
    return raw < 10_000_000_000 ? Math.trunc(raw * 1000) : Math.trunc(raw);
  }
  const value = stringValue(raw);
  if (!value) return 0;
  if (/^\d+$/.test(value)) {
    const numeric = Number(value);
    return numeric < 10_000_000_000 ? numeric * 1000 : numeric;
  }
  const parsed = Date.parse(value);
  return Number.isNaN(parsed) ? 0 : parsed;
}

function emptyUpdateResponse({ currentVersion, checkedAt, edition, source }) {
  return {
    ok: true,
    currentVersion,
    latestVersion: currentVersion,
    hasUpdate: false,
    checkedAt,
    publishedAt: 0,
    tag: "",
    track: "",
    releaseUrl: "",
    releaseNotes: "",
    apkName: "",
    apkDownloadUrl: "",
    edition,
    source,
    assets: [],
  };
}

function defaultStats() {
  return {
    totalChecks: 0,
    byDay: {},
    byVersion: {},
    byEdition: {},
    bySource: {},
    deletedTags: 0,
    lastCheckedAt: 0,
    lastDeletedAt: 0,
  };
}

function increment(bucket, key) {
  const safeKey = key || "unknown";
  bucket[safeKey] = (bucket[safeKey] || 0) + 1;
}

function releaseKey(tag) {
  return `release:${tag}`;
}

function stringValue(value) {
  if (value === null || value === undefined) return "";
  return String(value).trim();
}

async function readJson(request) {
  try {
    return await request.json();
  } catch {
    throw httpError(400, "Invalid JSON body");
  }
}

function httpError(status, message) {
  const error = new Error(message);
  error.status = status;
  return error;
}

function json(payload, status = 200) {
  return new Response(JSON.stringify(payload), {
    status,
    headers: JSON_HEADERS,
  });
}
