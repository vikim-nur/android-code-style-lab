"""Check both candidate profiles on owned fixture copies; never format an app checkout."""
import argparse,hashlib,json,shutil,subprocess,tempfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]

def main():
 p=argparse.ArgumentParser();p.add_argument('--cli',required=True,type=Path);p.add_argument('--rules',required=True,type=Path);p.add_argument('--java',default='java');p.add_argument('--output',required=True,type=Path);args=p.parse_args()
 cli=args.cli.resolve();rules=args.rules.resolve();out=args.output.resolve();out.mkdir(parents=True,exist_ok=True);records=[]
 for profile in ['nur-reference','ktlint-official']:
  work=out/profile;work.mkdir(exist_ok=True)
  config=ROOT/'config/profiles'/(profile+'.editorconfig');(work/'.editorconfig').write_bytes(config.read_bytes())
  for file in (ROOT/'examples/candidates/input').glob('*.kt'):shutil.copyfile(file,work/file.name)
  command=[args.java,'-jar',str(cli),'--ruleset='+str(rules),'--log-level=none','--relative']
  timings={}
  import time
  for phase,extra in [('format',['--format']),('repeat',['--format']),('check',[])]:
   start=time.monotonic();result=subprocess.run(command+extra+['*.kt'],cwd=work,capture_output=True,timeout=180);timings[phase]=round(time.monotonic()-start,3)
   (work/(phase+'.stdout')).write_bytes(result.stdout);(work/(phase+'.stderr')).write_bytes(result.stderr)
   if result.returncode:raise RuntimeError(profile+' '+phase+' failed; inspect logs in '+str(work))
   hashes={f.name:hashlib.sha256(f.read_bytes()).hexdigest() for f in work.glob('*.kt')}
   if phase=='format':expected=hashes
   elif hashes!=expected:raise RuntimeError('Unstable '+profile+' '+phase)
  records.append(dict(profile=profile,files=len(expected),format_exit=0,repeat_stable=True,check_exit=0,hashes=expected,timing_seconds=timings,config_sha256=hashlib.sha256(config.read_bytes()).hexdigest()))
 proof=dict(status='passed',engine='ktlint 1.8.0',rules_sha256=hashlib.sha256(rules.read_bytes()).hexdigest(),profiles=records,application_checkout_changed=False,ui_or_ci_tested=False)
 (out/'verification.json').write_text(json.dumps(proof,indent=2)+'\n');print(json.dumps(proof,indent=2))
if __name__=='__main__':main()
