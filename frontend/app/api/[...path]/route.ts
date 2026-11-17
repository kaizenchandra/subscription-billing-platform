import {NextRequest} from 'next/server';
export async function GET(request:NextRequest,context:{params:Promise<{path:string[]}>}){return forward(request,context)}
export async function POST(request:NextRequest,context:{params:Promise<{path:string[]}>}){return forward(request,context)}
async function forward(req:NextRequest,ctx:{params:Promise<{path:string[]}>}){
 const {path}=await ctx.params;
 const headers=new Headers({'Content-Type':'application/json'});
 const auth=req.headers.get('authorization');if(auth)headers.set('authorization',auth);
 const key=req.headers.get('idempotency-key');if(key)headers.set('idempotency-key',key);
 try{const res=await fetch(`${process.env.BACKEND_URL||'http://localhost:8080'}/api/v1/${path.map(encodeURIComponent).join('/')}`,{method:req.method,headers,body:req.method==='POST'?await req.text():undefined,signal:AbortSignal.timeout(path.at(-1)==='pdf'?30000:15000),cache:'no-store'});
 const outgoing=new Headers({'content-type':res.headers.get('content-type')||'application/json','cache-control':'private, no-store','x-content-type-options':'nosniff'});
 for(const name of ['content-disposition','x-document-sha256']){const value=res.headers.get(name);if(value)outgoing.set(name,value)}
 return new Response(res.body,{status:res.status,headers:outgoing});
 }catch{return Response.json({detail:'Billing service unavailable. Preserve the request key before retrying.'},{status:503})}
}
