export type SystemFont = { id: string; name: string }
export function createSystemFonts(
  request: () => Promise<SystemFont[]>,
  fontSet: FontFaceSet,
  FontFaceType: typeof FontFace,
  indexedDB?: IDBFactory,
): {
  list(): Promise<SystemFont[]>
  load(id: string): Promise<void>
  import(file: File): Promise<SystemFont>
}
