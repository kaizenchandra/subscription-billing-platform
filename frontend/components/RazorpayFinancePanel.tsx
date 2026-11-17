"use client";
import {useEffect,useState} from 'react';
type Row=Record<string,string|number|boolean|null>;
export default function RazorpayFinancePanel({api}:{api:(path:string,body?:object,key?:string)=>Promise<any>}){
 const [sessions,setSessions]=useState<Row[]>([]),[events,setEvents]=useState<Row[]>([]),[selected,setSelected]=useState(''),[order,setOrder]=useState(''),[error,setError]=useState(''),[status,setStatus]=useState(''),[busy,setBusy]=useState(false);
 async function load(){try{const [s,e]=await Promise.all([api('admin/razorpay/checkouts'),api('admin/razorpay/events')]);setSessions(s);setEvents(e)}catch(e){setError(String(e))}}
 useEffect(()=>{void load()},[]);
 return <section aria-label="Razorpay finance"><h2>Razorpay sandbox operations</h2><p>Resolve an uncertain order by finding its receipt (the checkout ID) in your Razorpay test dashboard. This action fetches and verifies that order; it never creates a second order or marks an invoice paid.</p>
 {error&&<p role="alert" className="error">{error}</p>}{status&&<p role="status">{status}</p>}
 <form onSubmit={async e=>{e.preventDefault();setBusy(true);setError('');try{await api(`admin/razorpay/checkouts/${selected}/reconcile-order`,{orderId:order});setStatus('Order identity verified and linked. Capture must still be verified.');await load()}catch(e){setError(String(e))}finally{setBusy(false)}}}>
 <label>Uncertain checkout<select required value={selected} onChange={e=>setSelected(e.target.value)}><option value="">Choose checkout</option>{sessions.filter(s=>['UNKNOWN','CREATING'].includes(String(s.state))).map(s=><option key={String(s.id)} value={String(s.id)}>{s.id}</option>)}</select></label>
 <label>Existing Razorpay order ID<input required pattern="order_[A-Za-z0-9]+" value={order} onChange={e=>setOrder(e.target.value)} placeholder="order_…"/></label><button disabled={busy||!selected}>Verify existing order</button></form>
 <div className="scroll"><table><caption>Signed webhook processing</caption><thead><tr><th>Event</th><th>Order</th><th>Status</th></tr></thead><tbody>{events.map(e=><tr key={String(e.id)}><td>{e.event_type}</td><td>{e.order_id}</td><td>{e.status}</td></tr>)}</tbody></table></div><button className="secondary" onClick={load}>Refresh provider events</button>
 </section>;
}
