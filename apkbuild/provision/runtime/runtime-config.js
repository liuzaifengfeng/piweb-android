'use strict';
const fs = require('node:fs');
const path = require('node:path');
const config = JSON.parse(fs.readFileSync(path.join(__dirname, 'deployment.json'), 'utf8'));
if (config.install_dir !== __dirname) throw new Error('Installation moved; re-pair this directory before starting');
for (const key of ['web_port', 'policy_port']) {
  if (!Number.isInteger(config[key]) || config[key] < 1024 || config[key] > 65535) throw new Error('Invalid port');
}
if (!path.isAbsolute(config.agent_dir)) throw new Error('Invalid agent directory');
const key = process.argv[2];
if (!['web_port', 'policy_port', 'agent_dir'].includes(key)) throw new Error('Unknown config field');
process.stdout.write(String(config[key]));
