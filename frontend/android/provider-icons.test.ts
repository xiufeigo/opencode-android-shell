import { expect, test } from "bun:test"
import { readFileSync } from "node:fs"
import { iconNames } from "./types"
import { extraProviderIcons, isExtraProviderIcon, resolveProviderIcon } from "./resolve"

test("provider plans and regional services use their vendor's icon", () => {
  const families = [
    ["minimax", ["minimax-coding-plan"]],
    ["minimax-cn", ["minimax-cn-coding-plan"]],
    ["kimi-for-coding", ["kimi", "kimi-code-plan-cn", "kimi-code-plan-global"]],
    ["xiaomi", ["xiaomi-token-plan-cn", "xiaomi-token-plan-ams", "xiaomi-token-plan-sgp"]],
    ["alibaba", ["alibaba-coding-plan", "alibaba-token-plan"]],
    ["alibaba-cn", ["alibaba-coding-plan-cn", "alibaba-token-plan-cn"]],
    ["zai", ["zai-coding-plan"]],
    ["zhipuai", ["zhipuai-coding-plan"]],
    ["stepfun", ["stepfun-ai", "stepfun-step-plan", "stepfun-ai-step-plan"]],
    ["tencent", ["tencent-coding-plan", "tencent-token-plan", "tencent-tokenhub"]],
    ["volcengine", ["volcengine-coding-plan"]],
    ["perplexity", ["perplexity-agent"]],
    ["siliconflow", ["siliconflow-cn"]],
    ["llmgateway", ["llmgateway-providers"]],
  ] as const
  for (const [brand, variants] of families) {
    expect(resolveProviderIcon(brand)).toBe(brand)
    for (const id of variants) expect(resolveProviderIcon(id)).toBe(brand)
  }
})

test("canonical and Console providers use the same resolver without guessing unknown providers", () => {
  expect(resolveProviderIcon("console-minimax-cn-coding-plan")).toBe("minimax-cn")
  expect(resolveProviderIcon("custom-provider", "xiaomi-token-plan-cn")).toBe("xiaomi")
  expect(resolveProviderIcon("moonshotai", "unknown")).toBe("moonshotai")
  expect(resolveProviderIcon("opencode-go")).toBe("opencode-go")
  expect(resolveProviderIcon("synthetic")).toBeUndefined()
  expect(resolveProviderIcon("unrelated-xiaomi-token-plan")).toBeUndefined()
})

test("every supplemental icon resolves to an existing sprite symbol", () => {
  const sprite = readFileSync(new URL("./extra.svg", import.meta.url), "utf8")
  for (const icon of extraProviderIcons) {
    expect(isExtraProviderIcon(icon)).toBe(true)
    expect(sprite).toContain(`id="${icon}"`)
  }
  expect(isExtraProviderIcon("xiaomi")).toBe(false)
})

test("every recognized provider icon has a symbol in its selected sprite", () => {
  const stock = readFileSync(new URL("./sprite.svg", import.meta.url), "utf8")
  const extra = readFileSync(new URL("./extra.svg", import.meta.url), "utf8")
  for (const id of iconNames) {
    const icon = resolveProviderIcon(id)
    if (!icon) {
      expect(id).toBe("synthetic")
      continue
    }
    expect(isExtraProviderIcon(icon) ? extra : stock).toContain(`id="${icon}"`)
  }
})
