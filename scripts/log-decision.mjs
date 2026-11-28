#!/usr/bin/env node
import fs from "node:fs"
import path from "node:path"

const [logPath, phase, decision, why, evidence, result] = process.argv.slice(2)
if (!logPath || !phase || !decision || !why || !evidence || !result) {
  console.error("usage: node scripts/log-decision.mjs <logfile> <phase> <decision> <why> <evidence> <result>")
  process.exit(2)
}

const header = "ts\tphase\tdecision\twhy\tevidence\tresult\n"
const cell = (value) => {
  const clean = String(value).replace(/[\t\r\n]+/g, " ").trim()
  return /^[=+\-@]/.test(clean) ? `'${clean}` : clean
}

fs.mkdirSync(path.dirname(logPath), { recursive: true })
if (!fs.existsSync(logPath)) fs.writeFileSync(logPath, header)
const row = [new Date().toISOString(), phase, decision, why, evidence, result].map(cell).join("\t") + "\n"
fs.appendFileSync(logPath, row)
