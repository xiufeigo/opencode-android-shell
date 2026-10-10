// Test-only plugin, loaded exclusively by the isolated test server. This probes
// the real registry through the public SDK; it is never in a distribution.
import { Plugin } from "@opencode/plugin"
import { Schema } from "effect"
import { Rpc } from "../../../plugins/server-browser/node_modules/@opencode/schema/dist/rpc.js"
import { randomUUID } from "node:crypto"

const portable = Schema.toStandardSchemaV1
const definition = Rpc.define({ id: "xiufeigo.server-browser-test", methods: { check: {
  input: portable(Schema.Struct({ sessionID: Schema.String })), output: portable(Schema.Json), errors: { probe: portable(Schema.Struct({})) },
} }, events: {} })

export default Plugin.define({ id: "xiufeigo.server-browser-test", async setup(ctx) {
  await ctx.rpc.register(definition, { async check(input, call) {
    try {
      const session = await ctx.session.get({ sessionID: input.sessionID })
      if (session.location.directory !== ctx.location.directory) throw new Error("Probe session belongs to another location")
      const tools = (await ctx.tool.list()).filter(tool => tool.options?.namespace?.startsWith("server_browser"))
      const run = name => tools.find(tool => `${tool.options?.namespace}.${tool.name}` === name).execute
      const context = { sessionID: input.sessionID, agent: "build", messageID: `msg_${randomUUID()}`, id: `call_${randomUUID()}`, signal: call.signal, progress: async () => {} }
      const opened = await run("server_browser.tabs.open")({}, context)
      const tabID = opened.output.id
      try {
        const listed = await run("server_browser.tabs.list")({}, context)
        return { tools: tools.map(tool => tool.id), tabID, listed: listed.output }
      } finally { await run("server_browser.tabs.close")({ tabID }, context) }
    } catch (error) { return call.error("probe", String(error), {}) }
  } })
} })
