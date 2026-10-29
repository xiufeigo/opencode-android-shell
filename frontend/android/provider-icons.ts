import { iconNames, type IconName } from "./types"

export const extraProviderIcons = ["llmgateway", "tencent", "volcengine"] as const
export type ProviderIconID = IconName | (typeof extraProviderIcons)[number]

const icons = new Map<string, ProviderIconID>(
  [...iconNames, ...extraProviderIcons].map((id) => [id, id]),
)

// These IDs are distinct services of the same provider, not model names offered by resellers.
const aliases = new Map<string, ProviderIconID>([
  ["alibaba-coding-plan-cn", "alibaba-cn"],
  ["alibaba-token-plan-cn", "alibaba-cn"],
  ["azure-cognitive-services", "azure"],
  ["google-vertex-anthropic", "google-vertex"],
  ["kimi", "kimi-for-coding"],
  ["kimi-code-plan-cn", "kimi-for-coding"],
  ["kimi-code-plan-global", "kimi-for-coding"],
  ["llmgateway-providers", "llmgateway"],
  ["perplexity-agent", "perplexity"],
  ["siliconflow-cn", "siliconflow"],
  ["stepfun-ai", "stepfun"],
  ["tencent-tokenhub", "tencent"],
])

function lookup(id: string | undefined): ProviderIconID | undefined {
  if (!id) return
  const name = id.replace(/^console-/, "")
  const alias = aliases.get(name)
  if (alias) return alias

  // Prefer the vendor icon to plan-specific placeholders when the vendor is known.
  const base = name.replace(/-(?:coding|token|step)-plan(?:-(?:cn|global|ams|sgp))?$/, "")
  if (base !== name) {
    const icon = icons.get(aliases.get(base) ?? base)
    if (icon && icon !== "synthetic") return icon
  }
  const icon = icons.get(name)
  return icon === "synthetic" ? undefined : icon
}

export function resolveProviderIcon(id: string, canonical?: string): ProviderIconID | undefined {
  return lookup(canonical) ?? lookup(id)
}

export function isExtraProviderIcon(id: string | undefined): boolean {
  return extraProviderIcons.some((icon) => icon === id)
}
