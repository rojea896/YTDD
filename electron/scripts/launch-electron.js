// Launches Electron as a real Electron app rather than as plain Node.
//
// VS Code (and some other Electron-based hosts) export ELECTRON_RUN_AS_NODE=1
// into their integrated terminals. If that variable survives into our launch,
// the electron binary starts in Node mode: `require('electron')` then resolves
// to the npm shim — which exports the path to electron.exe as a *string* — so
// `app`, `BrowserWindow` and everything else come back undefined. Stripping the
// variable here makes `npm run dev` behave identically inside and outside VS Code.
const { spawn } = require('child_process');

const electronPath = require('electron');

if (typeof electronPath !== 'string') {
  console.error('Expected the electron npm shim to export a path. Run this with node, not electron.');
  process.exit(1);
}

const env = { ...process.env };
delete env.ELECTRON_RUN_AS_NODE;

const args = process.argv.slice(2);
const child = spawn(electronPath, args.length > 0 ? args : ['.'], {
  stdio: 'inherit',
  env,
  windowsHide: false,
});

child.on('close', (code, signal) => {
  if (code === null) {
    console.error('electron exited with signal', signal);
    process.exit(1);
  }
  process.exit(code);
});

for (const signal of ['SIGINT', 'SIGTERM']) {
  process.on(signal, () => child.kill(signal));
}
