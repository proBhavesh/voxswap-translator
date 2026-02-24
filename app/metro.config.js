const { getDefaultConfig } = require('expo/metro-config');
const { withNativeWind } = require('nativewind/metro');
const path = require('path');
const fs = require('fs');

const config = getDefaultConfig(__dirname);

/* whisper.rn has a broken "exports" field:
   - No "." entry (bare import falls back with warning — OK)
   - "./*" glob resolves directories without appending /index.js
   - Adapters directory has no index.ts barrel file
   We intercept all whisper.rn imports and resolve to src/ directly. */
const whisperRoot = fs.realpathSync(
  path.join(__dirname, 'node_modules', 'whisper.rn'),
);

config.resolver.resolveRequest = (context, moduleName, platform) => {
  if (moduleName === 'whisper.rn' || moduleName.startsWith('whisper.rn/')) {
    let subpath;
    if (moduleName === 'whisper.rn') {
      subpath = 'src/index';
    } else {
      const rest = moduleName.slice('whisper.rn/'.length);
      subpath = rest.startsWith('src/') ? rest : `src/${rest}`;
    }

    const resolved = path.join(whisperRoot, subpath);

    return context.resolveRequest(
      { ...context, resolveRequest: undefined },
      resolved,
      platform,
    );
  }

  return context.resolveRequest(context, moduleName, platform);
};

module.exports = withNativeWind(config, { input: './src/global.css' });
