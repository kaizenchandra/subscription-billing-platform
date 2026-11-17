"use client";
import {useState} from 'react';
type Row=Record<string,string|number|boolean>;
type Props={subscription:Row;plans:Row[];preview:(plan:string,quantity:number,mode:string)=>Promise<void>};
export default function PlanChangeForm({subscription,plans,preview}:Props){
 const [plan,setPlan]=useState(String(subscription.plan));
 const [quantity,setQuantity]=useState(String(subscription.quantity));
 const [mode,setMode]=useState('NOW');
 const [busy,setBusy]=useState(false),[error,setError]=useState('');
 return <form onSubmit={async e=>{e.preventDefault();setBusy(true);setError('');try{await preview(plan,Number(quantity),mode)}catch(e){setError(e instanceof Error?e.message:'Unable to preview change')}finally{setBusy(false)}}}>
  <h3>Change plan or seats</h3>
  <p>Immediate upgrades require paid invoices. Downgrades take effect at renewal. Currency and billing interval stay unchanged.</p>
  <div className="grid">
   <label>New plan<select value={plan} onChange={e=>setPlan(e.target.value)}>{plans.filter(p=>p.currency===subscription.currency&&p.months===subscription.months).map(p=><option key={String(p.id)} value={String(p.id)}>{p.name}</option>)}</select></label>
   <label>Seats<input type="number" min="1" max="10000" step="1" required value={quantity} onChange={e=>setQuantity(e.target.value)}/></label>
   <label>Charge timing<select value={mode} onChange={e=>setMode(e.target.value)}><option value="NOW">Immediately · prorated</option><option value="RENEWAL">Next renewal · no immediate charge</option></select></label>
  </div>
  {error&&<p role="alert" className="error">{error}</p>}
  <button className="secondary" disabled={busy}>{busy?'Calculating…':'Preview exact charges'}</button>
 </form>;
}
