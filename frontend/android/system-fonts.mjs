const idPattern = /^oc(?:sys|imp)-[a-f0-9]{64}$/;
const importedIDPattern = /^ocimp-[a-f0-9]{64}$/;
const maxImportedFontBytes = 32 * 1024 * 1024;
const databaseName = "opencode.android.fonts";
const databases = new WeakMap();

// Register faces lazily: listing does not read the potentially large font files.
export function createSystemFonts(request, fontSet, FontFaceType, indexedDB) {
  let catalog;
  const faces = new Map();

  const list = () => {
    if (catalog) return catalog;
    catalog = Promise.all([request(), readImported(indexedDB)]).then(
      ([fonts, imported]) => {
        if (!Array.isArray(fonts)) throw new Error("Invalid system font list");
        const seen = new Set();
        for (const font of fonts) {
          if (
            !font ||
            typeof font.id !== "string" ||
            !idPattern.test(font.id) ||
            typeof font.name !== "string" ||
            !font.name ||
            seen.has(font.id)
          ) {
            throw new Error("Invalid system font entry");
          }
          seen.add(font.id);
        }
        const stored = validateImported(imported, seen);
        for (const font of fonts)
          register(font, `url("/oc-system-font/${font.id}")`);
        for (const font of stored) register(font, font.bytes);
        return [...fonts, ...stored.map(({ id, name }) => ({ id, name }))];
      },
    );
    return catalog;
  };

  return {
    list,
    async load(id) {
      await list();
      const face = faces.get(id);
      if (!face) throw new Error(`System font is unavailable: ${id}`);
      await face.load();
    },
    async import(file) {
      if (!indexedDB) throw new Error("fontStorageUnavailable");
      if (
        !file ||
        typeof file.name !== "string" ||
        !/\.(ttf|otf|ttc|otc)$/i.test(file.name)
      ) {
        throw new Error("fontImportInvalid");
      }
      if (file.size > maxImportedFontBytes)
        throw new Error("fontImportTooLarge");
      const bytes = await file.arrayBuffer();
      const digest = await crypto.subtle.digest("SHA-256", bytes);
      const id = `ocimp-${Array.from(new Uint8Array(digest), (byte) => byte.toString(16).padStart(2, "0")).join("")}`;
      await list();
      const existing = faces.get(id);
      if (existing) {
        await existing.load();
        return { id, name: file.name };
      }
      const font = { id, name: file.name, bytes };
      const face = register(font, bytes);
      try {
        await face.load();
        await writeImported(indexedDB, font);
      } catch (error) {
        fontSet.delete(face);
        faces.delete(id);
        throw error;
      }
      const current = await catalog;
      catalog = Promise.resolve([...current, { id, name: file.name }]);
      return { id, name: file.name };
    },
  };

  function register(font, source) {
    const face = new FontFaceType(font.id, source, { weight: "100 900" });
    fontSet.add(face);
    faces.set(font.id, face);
    return face;
  }
}

function validateImported(fonts, seen) {
  if (!Array.isArray(fonts)) throw new Error("Invalid imported font store");
  return fonts.map((font) => {
    if (
      !font ||
      !importedIDPattern.test(font.id) ||
      typeof font.name !== "string" ||
      !font.name ||
      !(font.bytes instanceof ArrayBuffer) ||
      seen.has(font.id)
    )
      throw new Error("Invalid imported font entry");
    seen.add(font.id);
    return font;
  });
}

async function readImported(indexedDB) {
  if (!indexedDB) return [];
  const database = await openDatabase(indexedDB);
  return requestValue(
    database.transaction("fonts", "readonly").objectStore("fonts").getAll(),
  );
}

async function writeImported(indexedDB, font) {
  const database = await openDatabase(indexedDB);
  await requestValue(
    database.transaction("fonts", "readwrite").objectStore("fonts").put(font),
  );
}

function openDatabase(indexedDB) {
  const existing = databases.get(indexedDB);
  if (existing) return existing;
  const database = new Promise((resolve, reject) => {
    const request = indexedDB.open(databaseName, 1);
    request.onupgradeneeded = () =>
      request.result.createObjectStore("fonts", { keyPath: "id" });
    request.onsuccess = () => resolve(request.result);
    request.onerror = () =>
      reject(request.error ?? new Error("Cannot open imported font storage"));
  });
  databases.set(indexedDB, database);
  return database;
}

function requestValue(request) {
  return new Promise((resolve, reject) => {
    request.onsuccess = () => resolve(request.result);
    request.onerror = () =>
      reject(
        request.error ?? new Error("Imported font storage request failed"),
      );
  });
}
