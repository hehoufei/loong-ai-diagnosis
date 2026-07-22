const { chromium } = require('playwright');
const path = require('path');

const outputDir = path.resolve(__dirname, 'screenshots');
const pages = [
  ['01-home', 'home.html'],
  ['02-ai-diagnosis', 'index.html'],
  ['03-storage-test', 'storage-task.html'],
  ['04-quick-task', 'quick-task.html'],
  ['05-ql-tool', 'ql-tool.html'],
  ['06-agent-chat', 'chat.html'],
  ['07-rules', 'rules.html'],
  ['08-docs', 'docs.html'],
];

(async () => {
  const browser = await chromium.launch({
    headless: true,
    executablePath: 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe',
    args: ['--disable-gpu', '--no-sandbox'],
  });
  const context = await browser.newContext({ viewport: { width: 1600, height: 900 }, deviceScaleFactor: 1 });
  await context.addInitScript(() => sessionStorage.setItem('wcs_logged_in', 'true'));
  for (const [name, file] of pages) {
    const page = await context.newPage();
    await page.goto(`http://localhost:18088/${file}`, { waitUntil: 'domcontentloaded', timeout: 20000 });
    await page.waitForTimeout(1800);
    await page.screenshot({ path: path.join(outputDir, `${name}.png`), fullPage: false });
    await page.close();
  }
  await browser.close();
})();
