import {test,expect} from '@playwright/test';
test('sign-in and real catalog',async({page})=>{await page.goto('http://localhost:3000');await page.getByLabel('Password',{exact:true}).fill('local-only');await page.getByRole('button',{name:'Open workspace'}).click();await page.getByRole('button',{name:'Plans',exact:true}).click();await expect(page.getByRole('heading',{name:'Starter',exact:true})).toBeVisible();});
test('lost collection response is reconciled once and invoice is visible',async({page,request})=>{
 const headers={Authorization:'Basic '+Buffer.from('demo:local-only').toString('base64'),'Idempotency-Key':crypto.randomUUID()};
 const checkout=await request.post('http://localhost:3000/api/subscriptions',{headers,data:{plan:'starter-v1',quantity:1,trialDays:0,zone:'UTC'}});expect(checkout.ok()).toBeTruthy();const sub=await checkout.json();
 let invoice:any;
 await expect.poll(async()=>{const res=await request.get('http://localhost:3000/api/invoices',{headers});const list=await res.json();invoice=list.find((i:any)=>i.subscription===sub.id);return !!invoice;}).toBeTruthy();
 const collect=await request.post(`http://localhost:3000/api/invoices/${invoice.id}/collect`,{headers:{...headers,'Idempotency-Key':crypto.randomUUID()},data:{scenario:'ACCEPTED_RESPONSE_LOST'}});expect(collect.ok()).toBeTruthy();const payment=await collect.json();
 await expect.poll(async()=>{const res=await request.get('http://localhost:3000/api/payments',{headers});return (await res.json()).find((p:any)=>p.id===payment.id).state;}).toBe('UNKNOWN');
 const run=await request.post('http://localhost:3000/api/admin/reconciliation/run',{headers,data:{}});expect(run.ok()).toBeTruthy();
 const replay=await request.post('http://localhost:3000/api/admin/reconciliation/run',{headers,data:{}});expect((await replay.json()).recovered).toBe(0);
 await page.goto('http://localhost:3000');await page.getByLabel('Password',{exact:true}).fill('local-only');await page.getByRole('button',{name:'Open workspace'}).click();await page.getByRole('button',{name:'Invoices',exact:true}).click();await expect(page.getByRole('row').filter({has:page.getByRole('cell',{name:`INV-${invoice.number}`,exact:true})})).toContainText('PAID');
 await page.setViewportSize({width:390,height:844});await expect(page.getByRole('heading',{name:'Invoices',exact:true})).toBeVisible();
});
test('upgrade preview confirms exact charge and produces auditable invoice lines',async({page,request})=>{
 const headers={Authorization:'Basic '+Buffer.from('demo:local-only').toString('base64'),'Idempotency-Key':crypto.randomUUID()};
 const checkout=await request.post('http://localhost:3000/api/subscriptions',{headers,data:{plan:'starter-v1',quantity:1,trialDays:0,zone:'UTC'}});expect(checkout.ok()).toBeTruthy();const sub=await checkout.json();
 let invoice:any;
 await expect.poll(async()=>{const res=await request.get('http://localhost:3000/api/invoices',{headers});invoice=(await res.json()).find((i:any)=>i.subscription===sub.id);return !!invoice;}).toBeTruthy();
 const pay=await request.post(`http://localhost:3000/api/invoices/${invoice.id}/collect`,{headers,data:{scenario:'SUCCESS'}});expect(pay.ok()).toBeTruthy();
 await expect.poll(async()=>{const res=await request.get(`http://localhost:3000/api/invoices/${invoice.id}`,{headers});return (await res.json()).status;}).toBe('PAID');
 await page.goto('http://localhost:3000');await page.getByLabel('Password',{exact:true}).fill('local-only');await page.getByRole('button',{name:'Open workspace'}).click();await page.getByRole('button',{name:'Subscriptions',exact:true}).click();
 const card=page.locator(`[data-subscription-id="${sub.id}"]`);
 await card.getByLabel('New plan').selectOption('professional-v1');await card.getByRole('button',{name:'Preview exact charges'}).click();
 const dialog=page.getByRole('dialog');await expect(dialog).toContainText('Unused old-plan credit:');await expect(dialog).toContainText('Exact invoice adjustment now:');await expect(dialog).toContainText('₹1,499.00');
 await dialog.getByRole('button',{name:'Confirm',exact:true}).click();await expect(dialog).not.toBeVisible();await expect(card.getByRole('heading',{name:'professional-v1',exact:true})).toBeVisible();
 const invoices=await (await request.get('http://localhost:3000/api/invoices',{headers})).json();const adjustment=invoices.find((i:any)=>i.subscription===sub.id&&i.kind==='PRORATION');expect(adjustment).toBeTruthy();
 const lines=await (await request.get(`http://localhost:3000/api/invoices/${adjustment.id}/lines`,{headers})).json();expect(lines).toHaveLength(2);expect(lines.reduce((n:number,line:any)=>n+line.amount,0)).toBe(adjustment.total);
});
test('hard decline recovery portal requires an explicit simulated method update',async({page,request})=>{
 const headers={Authorization:'Basic '+Buffer.from('demo:local-only').toString('base64'),'Idempotency-Key':crypto.randomUUID()};
 const checkout=await request.post('http://localhost:3000/api/subscriptions',{headers,data:{plan:'starter-v1',quantity:1,trialDays:0,zone:'UTC'}});expect(checkout.ok()).toBeTruthy();const sub=await checkout.json();
 let invoice:any;
 await expect.poll(async()=>{const list=await (await request.get('http://localhost:3000/api/invoices',{headers})).json();invoice=list.find((i:any)=>i.subscription===sub.id);return !!invoice;}).toBeTruthy();
 const payment=await (await request.post(`http://localhost:3000/api/invoices/${invoice.id}/collect`,{headers,data:{scenario:'HARD_DECLINE'}})).json();
 await expect.poll(async()=>{const list=await (await request.get('http://localhost:3000/api/customers/me/dunning',{headers})).json();return list.find((d:any)=>d.payment===payment.id)?.status;}).toBe('ACTION_REQUIRED');
 await page.goto('http://localhost:3000');await page.getByLabel('Password',{exact:true}).fill('local-only');await page.getByRole('button',{name:'Open workspace'}).click();await page.getByRole('button',{name:'Recovery',exact:true}).click();
 const card=page.locator(`[data-payment-id="${payment.id}"]`);await expect(card).toContainText('HARD_DECLINE');await expect(card.getByRole('button',{name:'Retry payment',exact:true})).toBeDisabled();
 await card.getByRole('button',{name:'Replace simulated payment method',exact:true}).click();await expect(page.getByRole('dialog')).toContainText('does not collect real card details');await page.getByRole('dialog').getByRole('button',{name:'Confirm',exact:true}).click();await expect(page.getByRole('dialog')).not.toBeVisible();
 await card.getByRole('button',{name:'Retry payment',exact:true}).click();await page.getByRole('dialog').getByRole('button',{name:'Confirm',exact:true}).click();await expect(page.getByRole('dialog')).not.toBeVisible();
 await expect.poll(async()=>{const p=await (await request.get(`http://localhost:3000/api/invoices/${invoice.id}`,{headers})).json();return p.status;}).toBe('PAID');
 await page.getByRole('button',{name:'Refresh',exact:true}).click();await expect(card).toContainText('RECOVERED');
 await card.getByRole('button',{name:'Inspect attempts',exact:true}).click();await expect(card.getByRole('table')).toContainText('SUCCEEDED');await expect(card.getByRole('row')).toHaveCount(3);
 await page.getByRole('button',{name:'Finance',exact:true}).click();await expect(page.getByRole('region',{name:'Finance dunning queue'}).locator(`[data-payment-id="${payment.id}"]`)).toContainText('RECOVERED');
});
test('unknown payment portal blocks repeat collection and simulated method replacement',async({page,request})=>{
 const headers={Authorization:'Basic '+Buffer.from('demo:local-only').toString('base64'),'Idempotency-Key':crypto.randomUUID()};
 const sub=await (await request.post('http://localhost:3000/api/subscriptions',{headers,data:{plan:'starter-v1',quantity:1,trialDays:0,zone:'UTC'}})).json();let invoice:any;
 await expect.poll(async()=>{invoice=(await (await request.get('http://localhost:3000/api/invoices',{headers})).json()).find((i:any)=>i.subscription===sub.id);return !!invoice;}).toBeTruthy();
 const p=await (await request.post(`http://localhost:3000/api/invoices/${invoice.id}/collect`,{headers,data:{scenario:'OUTAGE'}})).json();
 await expect.poll(async()=>{const list=await (await request.get('http://localhost:3000/api/customers/me/dunning',{headers})).json();return list.find((d:any)=>d.payment===p.id)?.status;}).toBe('UNKNOWN');
 await page.goto('http://localhost:3000');await page.getByLabel('Password',{exact:true}).fill('local-only');await page.getByRole('button',{name:'Open workspace'}).click();await page.getByRole('button',{name:'Recovery',exact:true}).click();
 const card=page.locator(`[data-payment-id="${p.id}"]`);await expect(card).toContainText('Do not submit another payment');await expect(card.getByRole('button',{name:'Retry payment',exact:true})).toBeDisabled();await expect(card.getByRole('button',{name:'Replace simulated payment method',exact:true})).toBeDisabled();
});
test('payment methods keeps card entry off the platform',async({page})=>{
 await page.goto('http://localhost:3000');await page.getByLabel('Password',{exact:true}).fill('local-only');await page.getByRole('button',{name:'Open workspace'}).click();await page.getByRole('button',{name:'Payment methods',exact:true}).click();
 await expect(page.getByRole('heading',{name:'Payment methods · Razorpay sandbox'})).toBeVisible();await expect(page.getByText('No card number, CVV, bank credentials, or reusable token is stored here.',{exact:false})).toBeVisible();
});
test('invoice PDF downloads as binary through the authenticated portal proxy',async({page,request})=>{
 const headers={Authorization:'Basic '+Buffer.from('demo:local-only').toString('base64'),'Idempotency-Key':crypto.randomUUID()};
 const checkout=await request.post('http://localhost:3000/api/subscriptions',{headers,data:{plan:'starter-v1',quantity:1,trialDays:0,zone:'UTC'}});expect(checkout.ok()).toBeTruthy();const sub=await checkout.json();let invoice:any;
 await expect.poll(async()=>{invoice=(await (await request.get('http://localhost:3000/api/invoices',{headers})).json()).find((i:any)=>i.subscription===sub.id);return !!invoice;}).toBeTruthy();
 await page.goto('http://localhost:3000');await page.getByLabel('Password',{exact:true}).fill('local-only');await page.getByRole('button',{name:'Open workspace'}).click();await page.getByRole('button',{name:'Invoices',exact:true}).click();
 const downloadPromise=page.waitForEvent('download');await page.getByRole('button',{name:`Download PDF for INV-${invoice.number}`,exact:true}).click();const download=await downloadPromise;expect(download.suggestedFilename()).toBe(`invoice-INV-${invoice.number}.pdf`);
 const stream=await download.createReadStream();const chunks:Buffer[]=[];for await(const chunk of stream!)chunks.push(Buffer.from(chunk));const bytes=Buffer.concat(chunks);expect(bytes.subarray(0,5).toString()).toBe('%PDF-');expect(bytes.length).toBeGreaterThan(1000);
 const cached=await request.get(`http://localhost:3000/api/invoices/${invoice.id}/pdf`,{headers});expect(cached.status()).toBe(200);expect(cached.headers()['content-type']).toContain('application/pdf');expect(cached.headers()['cache-control']).toContain('no-store');expect(await cached.body()).toEqual(bytes);
 const cross={Authorization:'Basic '+Buffer.from('customer:local-only').toString('base64')};expect((await request.get(`http://localhost:3000/api/invoices/${invoice.id}/pdf`,{headers:cross})).status()).toBe(404);
 await page.getByRole('button',{name:'Finance',exact:true}).click();await expect(page.getByRole('button',{name:`Download finance PDF for INV-${invoice.number}`,exact:true})).toBeVisible();
});
