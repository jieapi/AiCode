const fs = require('node:fs');
const path = require('node:path');
const Ajv2020 = require('ajv/dist/2020');
const semver = require('semver');

const root = path.join(__dirname, '..');
const schema = JSON.parse(fs.readFileSync(path.join(__dirname, 'plugin.schema.json'), 'utf8'));
const validateSchema = new Ajv2020({ allErrors: true, strict: false }).compile(schema);
const HOST_API_VERSION = '1.0.0';

function validateManifest(manifest) {
  const errors = [];
  if (!validateSchema(manifest)) {
    errors.push(...validateSchema.errors.map((error) => `${error.instancePath}: ${error.message}`));
  }
  const range = manifest.engines?.pluginApi;
  if (typeof range === 'string' && !semver.satisfies(HOST_API_VERSION, range)) {
    errors.push(`engines.pluginApi: ${range} does not include ${HOST_API_VERSION}`);
  }
  const contributes = manifest.contributes ?? {};
  const needsRuntime = ['tools', 'hooks', 'executionBackends', 'workflows', 'sessions']
    .some((key) => contributes[key]?.length > 0) || contributes.uiEntries?.some((entry) => entry.handler);
  if (needsRuntime && !manifest.runtime) errors.push('runtime: required by runtime contributions');
  return errors;
}

function checkFixtures() {
  const fixtures = JSON.parse(fs.readFileSync(path.join(root, 'sdk/test/fixtures/contracts.json'), 'utf8'));
  const errors = [];
  for (const [key, valid] of [['validManifests', true], ['invalidManifests', false]]) {
    if (!Array.isArray(fixtures[key]) || fixtures[key].length === 0) {
      errors.push(`${key}: expected a nonempty array`);
      continue;
    }
    fixtures[key].forEach((manifest, index) => {
      const result = validateManifest(manifest);
      if ((result.length === 0) !== valid) errors.push(`${key}[${index}] ${manifest.id}: ${result.join('; ') || 'unexpectedly valid'}`);
    });
  }
  return { fixtures, errors };
}

function main() {
  const manifests = [path.join(root, 'template/plugin.json')];
  for (const entry of fs.readdirSync(path.join(root, 'samples'), { withFileTypes: true })) {
    const file = path.join(root, 'samples', entry.name, 'plugin.json');
    if (entry.isDirectory() && fs.existsSync(file)) manifests.push(file);
  }
  const errors = manifests.flatMap((file) => validateManifest(JSON.parse(fs.readFileSync(file, 'utf8')))
    .map((error) => `${path.relative(root, file)}: ${error}`));
  const checked = checkFixtures();
  errors.push(...checked.errors);
  if (errors.length) {
    console.error(errors.join('\n'));
    process.exitCode = 1;
  } else {
    console.log(`插件清单全部合法：${manifests.length} 份；PluginApi ${HOST_API_VERSION}；共享 fixture ${checked.fixtures.validManifests.length} 合法 / ${checked.fixtures.invalidManifests.length} 非法`);
  }
}

module.exports = { HOST_API_VERSION, validateManifest, checkFixtures };
if (require.main === module) main();
