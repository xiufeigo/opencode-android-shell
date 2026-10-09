import type { Platform } from "./platform"
export function openAttachmentPickerDialog<Value>(
  opts: Parameters<NonNullable<Platform["openAttachmentPickerDialog"]>>[0],
  onFile: (file: File) => Promise<Value>,
): Promise<void>
