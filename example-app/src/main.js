import { Capacitor } from '@capacitor/core';
import { CapacitorKmp } from 'capacitor-kotlin-multi-platform';

const $ = (id) => document.getElementById(id);

function renderKv(el, data) {
  el.replaceChildren(
    ...Object.entries(data).map(([key, value]) => {
      const row = document.createElement('div');
      const dt = document.createElement('dt');
      const dd = document.createElement('dd');
      dt.textContent = key;
      dd.textContent = String(value);
      row.append(dt, dd);
      return row;
    }),
  );
}

async function runInfo() {
  try {
    const info = await CapacitorKmp.getKmpInfo();
    renderKv($('info'), {
      'Capacitor platform': Capacitor.getPlatform(),
      'Kotlin platform': info.platform,
      'OS version': info.osVersion,
      'Core version': info.coreVersion,
      'Kotlin compiler': info.kotlinVersion,
      'Kotlin stdlib (runtime)': info.kotlinStdlibVersion,
    });
  } catch (e) {
    $('info').textContent = `Error: ${e.message}`;
  }
}

$('infoButton').addEventListener('click', runInfo);

$('echoButton').addEventListener('click', async () => {
  const result = await CapacitorKmp.echo({ value: $('echoInput').value });
  $('echoResult').textContent = JSON.stringify(result, null, 2);
  $('echoResult').classList.remove('muted');
});

$('errorButton').addEventListener('click', async () => {
  try {
    await CapacitorKmp.echo({});
    $('errorResult').textContent = 'Unexpectedly succeeded';
  } catch (e) {
    $('errorResult').textContent = `code: ${e.code}\nmessage: ${e.message}`;
    $('errorResult').classList.remove('muted');
  }
});

runInfo();
