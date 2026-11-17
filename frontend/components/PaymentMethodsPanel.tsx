"use client";
import {useEffect,useRef,useState} from 'react';
import Script from 'next/script';
type Row=Record<string,string|number|boolean|null>;
type Callback={razorpay_payment_id:string;razorpay_signature:string};
type CheckoutOptions={key:string;order_id:string;amount:number;currency:string;name:string;description:string;handler:(result:Callback)=>void;modal:{ondismiss:()=>void}};
declare global {interface Window {Razorpay?:new(options:CheckoutOptions)=>{open:()=>void;on:(event:string,listener:()=>void)=>void}}}
type Api=(path:string,body?:object,key?:string)=>Promise<any>;
export default function PaymentMethodsPanel({api,invoices,onChange}:{api:Api;invoices:Row[];onChange:()=>Promise<void>}){
 const [configured,setConfigured]=useState(false),[ready,setReady]=useState(false),[methods,setMethods]=useState<Row[]>([]),[sessions,setSessions]=useState<Row[]>([]),[error,setError]=useState(''),[status,setStatus]=useState(''),[busy,setBusy]=useState(false);
 const [pending,setPending]=useState<{kind:'CHECKOUT'|'ARCHIVE';row:Row}|null>(null);const dialog=useRef<HTMLDialogElement>(null);const keys=useRef<Record<string,string>>({});
 const key=(scope:string)=>keys.current[scope]||(keys.current[scope]=crypto.randomUUID());
 async function load(){try{const [p,m,s]=await Promise.all([api('payments/providers'),api('customers/me/payment-methods'),api('customers/me/checkouts')]);setConfigured(p.razorpayEnabled);setMethods(m);setSessions(s)}catch(e){setError(String(e))}}
 useEffect(()=>{void load()},[]);useEffect(()=>{if(pending)dialog.current?.showModal();else dialog.current?.close()},[pending]);
 async function open(session:Row){
  if(!window.Razorpay)throw new Error('Razorpay Checkout has not loaded. No payment was initiated.');
  if(session.last_status==='authorized'){setStatus('Payment is authorized and awaiting capture. Do not reopen Checkout while this authorization is unresolved.');return;}
  if(session.state!=='READY'){setStatus(`Checkout is ${session.state}. Do not create another order; finance must verify uncertain outcomes.`);return;}
  const checkout=new window.Razorpay({key:String(session.key_id),order_id:String(session.order_id),amount:Number(session.amount),currency:String(session.currency),name:'Accrual · Razorpay test mode',description:'Payment for your finalized invoice',
   handler:async result=>{setStatus('Verifying payment with Razorpay. Service activation is pending verified capture.');try{const verified=await api(`payments/checkouts/${session.id}/verify`,{paymentId:result.razorpay_payment_id,signature:result.razorpay_signature});setStatus(verified.state==='PAID'?'Payment capture verified. Invoice paid.':'Payment authorized; awaiting verified capture. Refresh to check status.');await load();await onChange()}catch(e){setError(String(e));setStatus('Verification is incomplete. Do not create another order; refresh or contact finance.')}},
   modal:{ondismiss:()=>{setStatus('Checkout closed. Resume the existing checkout if needed; closing it does not prove payment failure.')}}});
  checkout.on('payment.failed',()=>setStatus('Checkout reported a failed attempt. The invoice is not paid. Resume only the existing order after reviewing its status.'));
  checkout.open();
 }
 async function execute(){if(!pending)return;setBusy(true);setError('');try{
  if(pending.kind==='ARCHIVE'){await api(`customers/me/payment-methods/${pending.row.id}/archive`,{},key('archive:'+pending.row.id));setStatus('Local payment-method reference archived. Provider mandates and tokens were not revoked.');setPending(null);await load();return;}
  const session=await api('payments/setup',{invoice:pending.row.id},key('setup:'+pending.row.id));setPending(null);await load();await open(session);
 }catch(e){setError(String(e))}finally{setBusy(false)}}
 const unpaid=invoices.filter(i=>i.status==='OPEN'&&i.currency==='INR'&&Number(i.total)-Number(i.paid)-Number(i.credit)>=100);
 return <section aria-label="Payment methods">
  <h2>Payment methods · Razorpay sandbox</h2>
  <p>Enter payment details only in Razorpay-hosted Checkout. No card number, CVV, bank credentials, or reusable token is stored here. This integration supports customer-present invoice payments; it does not create a recurring mandate.</p>
  {!configured&&<p role="status">Razorpay sandbox is not configured. Add server-side test credentials to enable hosted Checkout. The local simulator remains available in Invoices.</p>}
  {configured&&<Script src="https://checkout.razorpay.com/v1/checkout.js" strategy="afterInteractive" onReady={()=>setReady(true)} onError={()=>setError('Could not load Razorpay Checkout. No new order was initiated.')}/>}
  {error&&<p className="error" role="alert">{error}</p>}{status&&<p role="status">{status}</p>}
  <button className="secondary" disabled={busy} onClick={async()=>{await load();await onChange()}}>Refresh payment methods</button>
  <h3>Pay an outstanding invoice</h3><p>The exact finalized invoice balance is charged now in test mode. There is no separate setup fee. Free-trial method setup without a charge is not supported.</p>
  {!unpaid.length&&<p>No outstanding invoices available for payment setup.</p>}
  {unpaid.map(i=><article className="card" key={String(i.id)} data-setup-invoice={String(i.id)}><strong>INV-{i.number}</strong><p>{new Intl.NumberFormat('en-IN',{style:'currency',currency:String(i.currency)}).format((Number(i.total)-Number(i.paid)-Number(i.credit))/100)} · due for collection now</p><button disabled={!configured||!ready||busy||sessions.some(s=>s.invoice===i.id)} onClick={()=>setPending({kind:'CHECKOUT',row:i})}>Pay with Razorpay test Checkout</button></article>)}
  <h3>Existing checkouts</h3>{sessions.map(s=><article className="card" key={String(s.id)} data-checkout-id={String(s.id)}><p>Checkout {s.id} · <strong>{s.state}</strong></p>{s.state==='READY'&&s.last_status!=='authorized'&&<button className="secondary" disabled={!ready||busy} onClick={async()=>{try{await open(await api(`payments/checkouts/${s.id}`))}catch(e){setError(String(e))}}}>Resume existing checkout</button>}{s.last_status==='authorized'&&s.state!=='PAID'&&<p>Payment is authorized and awaiting capture. Checkout is paused until the authorization is resolved.</p>}{['UNKNOWN','CREATING'].includes(String(s.state))&&<p>Provider outcome is uncertain. Do not create another order. Contact finance with this checkout ID.</p>}</article>)}
  <h3>Verified payment-method references</h3><p>These are receipts of previously used methods, not reusable payment credentials. Use Checkout on the next invoice to choose another method.</p>
  {!methods.length&&<p>No verified references yet. They appear only after a captured payment is verified.</p>}
  {methods.map(m=><article className="card" key={String(m.id)} data-method-id={String(m.id)}><p>{m.provider} · {m.method}{m.last4?` ending ${m.last4}`:''} · {m.archived?'Archived':'Verified reference'}</p><button className="secondary" disabled={!!m.archived||busy} onClick={()=>setPending({kind:'ARCHIVE',row:m})}>Archive local reference</button></article>)}
  <dialog ref={dialog} onCancel={()=>{if(!busy)setPending(null)}} aria-labelledby="method-confirmation-title"><h2 id="method-confirmation-title">{pending?.kind==='ARCHIVE'?'Archive local reference?':'Confirm test-mode payment'}</h2>
   <p>{pending?.kind==='ARCHIVE'?'This hides the local reference from active use. It does not revoke a provider token, cancel a mandate, or refund a payment.':`Open Razorpay-hosted Checkout to pay ${new Intl.NumberFormat('en-IN',{style:'currency',currency:String(pending?.row.currency||'INR')}).format((Number(pending?.row.total)-Number(pending?.row.paid)-Number(pending?.row.credit))/100)} for finalized invoice INV-${pending?.row.number} now? This is an INR test-mode payment. No recurring mandate or automatic renewal authorization is created.`}</p>
   {error&&<p role="alert">{error}</p>}<div className="actions"><button disabled={busy} onClick={execute}>{busy?'Working…':'Confirm'}</button><button className="secondary" disabled={busy} onClick={()=>setPending(null)}>Back</button></div>
  </dialog>
 </section>;
}
