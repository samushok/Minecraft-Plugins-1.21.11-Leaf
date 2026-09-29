#!/usr/bin/env python3
"""Build MogRitual against the patched Leaf 1.21.11 server and libraries."""
import argparse, os, subprocess, zipfile, shutil
from pathlib import Path

p=argparse.ArgumentParser()
p.add_argument('--server',type=Path,required=True)
p.add_argument('--libraries',type=Path,required=True)
p.add_argument('--javac',default='javac')
a=p.parse_args()

root=Path(__file__).resolve().parent
classes=root/'build/classes'
shutil.rmtree(classes, ignore_errors=True)
classes.mkdir(parents=True,exist_ok=True)

cp=os.pathsep.join([str(a.server.resolve()),*[str(x.resolve()) for x in a.libraries.rglob('*.jar')]])
sources=[str(x) for x in (root/'src/main/java').rglob('*.java')]
subprocess.run([a.javac,'--release','21','-proc:none','-Xlint:deprecation','-encoding','UTF-8','-cp',cp,'-d',str(classes),*sources],check=True)

out=root/'build/MogRitual-1.3-Leaf-1.21.11.jar'
with zipfile.ZipFile(out,'w',zipfile.ZIP_DEFLATED) as z:
    z.writestr('META-INF/MANIFEST.MF','Manifest-Version: 1.0\npaperweight-mappings-namespace: mojang\n\n')
    for folder in [classes,root/'src/main/resources']:
        for f in folder.rglob('*'):
            if f.is_file():
                z.write(f,f.relative_to(folder))
print(out)
