import {test,expect} from '@playwright/test';
import {createHmac} from 'node:crypto';
// Opt-in suite requires the test-classpath RazorpayPortalFixture, never real provider credentials.
test.skip(process.env.RAZORPAY_CONTRACT_TEST!=='true','Requires the isolated local Razorpay contract fixture');
test('hosted checkout callback is verified and references can be archived',async({page,request})=>{
 const headers={Authorization:'Basic '+Buffer.from('demo:local-only').toString('base64'),'Idempotency-Key':crypto.randomUUID()};
 const sub=await (await request.post('http://localhost:3000/api/subscriptions',{headers,data:{plan:'starter-v1',quantity:1,trialDays:0,zone:'UTC'}})).json();let invoice:any;
 await expect.poll(async()=>{invoice=(await (await request.get('http://localhost:3000/api/invoices',{headers})).json()).find((i:any)=>i.subscription===sub.id);return !!invoice;}).toBeTruthy();
 await page.route('https://checkout.razorpay.com/v1/checkout.js',route=>route.fulfill({contentType:'text/javascript',body:'window.Razorpay=function(options){window.contractCheckout=options;this.open=function(){};this.on=function(){}}'}));
 await page.goto('http://localhost:3000');await page.getByLabel('Password',{exact:true}).fill('local-only');await page.getByRole('button',{name:'Open workspace'}).click();await page.getByRole('button',{name:'Payment methods',exact:true}).click();
 await page.locator(`[data-setup-invoice="${invoice.id}"]`).getByRole('button',{name:'Pay with Razorpay test Checkout'}).click();await expect(page.getByRole('dialog')).toContainText('No recurring mandate');await page.getByRole('dialog').getByRole('button',{name:'Confirm',exact:true}).click();await expect(page.getByRole('dialog')).not.toBeVisible();
 let session:any;await expect.poll(async()=>{session=(await (await request.get('http://localhost:3000/api/customers/me/checkouts',{headers})).json()).find((c:any)=>c.invoice===invoice.id);return session?.state;}).toBe('READY');
 const paymentId='pay_'+session.order_id.substring(6),signature=createHmac('sha256','fixture-api-secret').update(session.order_id+'|'+paymentId).digest('hex');
 await page.evaluate(({paymentId,signature})=>(window as any).contractCheckout.handler({razorpay_payment_id:paymentId,razorpay_signature:signature}),{paymentId,signature});
 await expect(page.getByText('Payment capture verified. Invoice paid.',{exact:true})).toBeVisible();
 const methods=await (await request.get('http://localhost:3000/api/customers/me/payment-methods',{headers})).json();const method=methods[0];const card=page.locator(`[data-method-id="${method.id}"]`);await expect(card).toContainText('ending 1234');
 await card.getByRole('button',{name:'Archive local reference',exact:true}).click();await expect(page.getByRole('dialog')).toContainText('does not revoke a provider token');await page.getByRole('dialog').getByRole('button',{name:'Confirm',exact:true}).click();await expect(card).toContainText('Archived');
});
test('anonymous signed capture webhook pays once and rejects modified payload',async({request})=>{
 const headers={Authorization:'Basic '+Buffer.from('demo:local-only').toString('base64'),'Idempotency-Key':crypto.randomUUID()};
 const sub=await (await request.post('http://localhost:3000/api/subscriptions',{headers,data:{plan:'starter-v1',quantity:1,trialDays:0,zone:'UTC'}})).json();let invoice:any;
 await expect.poll(async()=>{invoice=(await (await request.get('http://localhost:3000/api/invoices',{headers})).json()).find((i:any)=>i.subscription===sub.id);return !!invoice;}).toBeTruthy();
 const session=await (await request.post('http://localhost:3000/api/payments/setup',{headers,data:{invoice:invoice.id}})).json();expect(session.state).toBe('READY');
 const body=JSON.stringify({account_id:'acc_fixture',event:'payment.captured',payload:{payment:{entity:{id:'pay_'+session.order_id.substring(6),order_id:session.order_id,amount:49900,currency:'INR',status:'captured',captured:true,method:'card',card:{last4:'1234'}}}}});
 const signed={'Content-Type':'application/json','X-Razorpay-Event-Id':crypto.randomUUID(),'X-Razorpay-Signature':createHmac('sha256','fixture-webhook-secret').update(body).digest('hex')};
 const url='http://localhost:18089/api/v1/webhooks/payments/razorpay';expect((await request.post(url,{headers:signed,data:body})).ok()).toBeTruthy();expect((await request.post(url,{headers:signed,data:body})).ok()).toBeTruthy();expect((await request.post(url,{headers:signed,data:body.replace('49900','59900')})).status()).toBe(401);
 await expect.poll(async()=>{return (await (await request.get(`http://localhost:3000/api/invoices/${invoice.id}`,{headers})).json()).status;}).toBe('PAID');
 const attempts=await (await request.get(`http://localhost:3000/api/payments/${session.id}/attempts`,{headers})).json();expect(attempts).toHaveLength(1);expect(attempts[0].state).toBe('SUCCEEDED');
});
