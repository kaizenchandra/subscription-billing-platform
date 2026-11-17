"""Simulator-only offered-load checkout benchmark; does not represent renewal TPS."""
import argparse,base64,concurrent.futures,json,time,urllib.request,statistics,uuid
p=argparse.ArgumentParser();p.add_argument('--requests',type=int,default=100);p.add_argument('--workers',type=int,default=8);p.add_argument('--url',default='http://localhost:8080');a=p.parse_args()
def run(_):
 start=time.perf_counter()
 req=urllib.request.Request(a.url+'/api/v1/subscriptions',data=json.dumps(dict(plan='starter-v1',quantity=1,trialDays=0,zone='UTC')).encode(),headers={'Authorization':'Basic '+base64.b64encode(b'demo:local-only').decode(),'Content-Type':'application/json','Idempotency-Key':str(uuid.uuid4())})
 try:
  with urllib.request.urlopen(req,timeout=20) as r:r.read()
  return (time.perf_counter()-start)*1000,True
 except Exception:return (time.perf_counter()-start)*1000,False
start=time.perf_counter()
with concurrent.futures.ThreadPoolExecutor(max_workers=a.workers) as pool:results=list(pool.map(run,range(a.requests)))
lat=sorted(t for t,ok in results);elapsed=time.perf_counter()-start
print(json.dumps(dict(requests=a.requests,successes=sum(ok for _,ok in results),seconds=elapsed,attempted_tps=len(results)/elapsed,achieved_tps=sum(ok for _,ok in results)/elapsed,p50_ms=statistics.median(lat),p95_ms=lat[min(len(lat)-1,int(len(lat)*.95))],p99_ms=lat[min(len(lat)-1,int(len(lat)*.99))]),indent=2))
