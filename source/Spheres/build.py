#!/usr/bin/env python3
"""Build against the Mojang-mapped server and libraries of a started Leaf 1.21.11 server."""
import argparse, os, subprocess, zipfile, shutil
from pathlib import Path
p=argparse.ArgumentParser()
p.add_argument('--server',type=Path,required=True,help='Patched server JAR from versions/1.21.11, not the launcher')
p.add_argument('--libraries',type=Path,required=True,help='libraries directory of the started server')
p.add_argument('--javac',default='javac',help='Java 21 javac executable')
a=p.parse_args();root=Path(__file__).resolve().parent;classes=root/'build/classes';shutil.rmtree(classes, ignore_errors=True);classes.mkdir(parents=True,exist_ok=True)
cp=os.pathsep.join([str(a.server.resolve()),*[str(x.resolve()) for x in a.libraries.rglob('*.jar')]])
subprocess.run([a.javac,'--release','21','-proc:none','-encoding','UTF-8','-cp',cp,'-d',str(classes),*[str(x) for x in (root/'src/main/java').rglob('*.java')]],check=True)
out=root/'build/Spheres-1.3.4-Leaf-1.21.11.jar'
with zipfile.ZipFile(out,'w',zipfile.ZIP_DEFLATED) as z:
 z.writestr('META-INF/MANIFEST.MF','Manifest-Version: 1.0\npaperweight-mappings-namespace: mojang\n\n')
 for folder in [classes,root/'src/main/resources']:
  for f in folder.rglob('*'):
   if f.is_file():z.write(f,f.relative_to(folder))
print(out)
