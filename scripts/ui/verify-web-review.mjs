// Focused controlled real-DOM review regressions, independent of native/live acceptance.
import { createRequire } from 'node:module';
import { checkReview } from './fixtures/web-review.mjs';
const require=createRequire(import.meta.url);
const {chromium}=require(process.env.PLAYWRIGHT_MODULE||'playwright');
const browser=await chromium.launch({executablePath:process.env.CHROMIUM_PATH,headless:true});
try {for(const issue of process.argv[2]?[process.argv[2]]:['I1','I2','I3'])await checkReview(browser,process.env.UI_BASE_URL||'http://127.0.0.1:5176',issue);}
finally {await browser.close();}
