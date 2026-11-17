"use client";
import {useState} from 'react';
type Row=Record<string,string|number|boolean|null>;
type Props={cases:Row[];notifications:Row[];entitlements:Row[];finance?:boolean;simulator:boolean;api:(path:string)=>Promise<Row[]>;confirm:(title:string,path:string,body?:object)=>void};
const date=(v:unknown)=>v?new Date(String(v)).toLocaleString():'None scheduled';
function money(v:unknown,currency:unknown){return new Intl.NumberFormat('en-IN',{style:'currency',currency:String(currency)}).format(Number(v)/100)}
export default function PaymentRecoveryPanel({cases,notifications,entitlements,finance=false,simulator,api,confirm}:Props){
 const [history,setHistory]=useState<Record<string,Row[]>>({}),[error,setError]=useState('');
 const now=Date.now();
 return <section aria-label={finance?'Finance dunning queue':'Payment recovery'}>
  <h2>{finance?'Dunning queue':'Payment recovery'}</h2>
  <p>Only verified temporary failures are retried automatically. Unknown outcomes require verification. Refresh to update pending attempts and retry availability.</p>
  {error&&<p role="alert" className="error">{error}</p>}
  {!cases.length&&<p>No payment recovery cases.</p>}
  {cases.map(c=>{
   const id=String(c.payment),budget=Number(c.attempt_count)<Number(c.max_attempts)&&now<new Date(String(c.retry_deadline)).getTime();
   const temporary=['SOFT_DECLINE','RATE_LIMIT','TEMPORARY_FAILURE'].includes(String(c.failure_class));
   const due=!!c.next_retry_at&&now>=new Date(String(c.next_retry_at)).getTime();
   const providerReady=!c.provider_not_before||now>=new Date(String(c.provider_not_before)).getTime();
   const canRetry=c.state==='DECLINED'&&budget&&providerReady&&((temporary&&due)||!!c.recovery_authorized);
   const access=entitlements.find(e=>e.id===c.subscription);
   return <article key={id} data-payment-id={id} className="card">
    <span className="badge">{c.status}</span><h3>Invoice INV-{c.number} · {money(c.amount,c.currency)}</h3>
    <p>Payment: {c.state} · {c.failure_class||'No failure'} · Attempts: {c.attempt_count} / {c.max_attempts}</p>
    <p>Next automatic retry: {date(c.next_retry_at)}. Overall retry deadline: {date(c.retry_deadline)}.</p>
    {c.provider_not_before&&<p>Provider permits another attempt after: {date(c.provider_not_before)}.</p>}
    <p>Grace period ends: {date(c.grace_until)}.{access&&` Current service access: ${access.access?'allowed':'restricted'}.`}</p>
    {c.state==='UNKNOWN'&&<p role="status">Payment is being verified. Do not submit another payment. Finance can reconcile the provider result.</p>}
    {c.status==='RECOVERED'&&<p role="status">Payment verified and invoice paid. Other outstanding invoices can still affect access.</p>}
    {c.status==='RESTRICTED'&&<p>Grace period expired. Resolve outstanding payments to restore eligible service access.</p>}
    {!budget&&c.status!=='RECOVERED'&&<p>The retry count or time budget is exhausted. Contact finance for an audited resolution.</p>}
    <div className="actions">
     <button disabled={!canRetry} onClick={()=>confirm(`Retry collection of ${money(c.amount,c.currency)} now? This creates one new provider attempt, within the existing retry budget.`,`${finance?'admin/':''}payments/${id}/retry`,{})}>Retry payment</button>
     {!finance&&<button className="secondary" disabled={!simulator||c.state!=='DECLINED'||!budget||!!c.recovery_authorized} onClick={()=>confirm('Replace the simulated payment method? This local-only flow stores an approved simulator reference and authorizes a manual retry. It does not collect real card details or initiate a charge.',`payments/${id}/simulator-method`,{})}>Replace simulated payment method</button>}
     {!finance&&<button className="secondary" onClick={async()=>{try{setHistory(h=>({...h,[id]:[]}));const rows=await api(`payments/${id}/attempts`);setHistory(h=>({...h,[id]:rows}));setError('')}catch(e){setError(String(e))}}}>Inspect attempts</button>}
    </div>
    {c.recovery_authorized&&<p>Updated simulator method is ready. Confirm a manual retry when provider timing permits.</p>}
    {history[id]&&<div className="scroll"><table><caption>Durable payment attempts</caption><thead><tr><th>Attempt</th><th>Outcome</th><th>Failure</th><th>Provider operation identity</th></tr></thead><tbody>{history[id].map(a=><tr key={String(a.id)}><td>{a.number}</td><td>{a.state}</td><td>{a.failure_class}</td><td>{a.id}</td></tr>)}</tbody></table></div>}
   </article>;
  })}
  {!finance&&<><h2>Billing notifications</h2>{!notifications.length&&<p>No billing notices.</p>}{notifications.map(n=><article className="card" key={String(n.id)}><strong>{String(n.kind).replaceAll('_',' ')}</strong><p>{n.message}</p><small>{date(n.created_at)}</small></article>)}</>}
 </section>;
}
