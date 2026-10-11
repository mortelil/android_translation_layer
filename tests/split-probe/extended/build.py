#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Host Android SDK: build independent signed manifest/feature counterexamples.
Usage: SDK_ROOT BUILD_TOOLS_VERSION DEBUG_KEYSTORE KEY_ALIAS OUTPUT_DIR
The supplied key is a TEST key with password android. OUTPUT_DIR must be new.
"""
from pathlib import Path
import os, shutil, subprocess as sp, sys, zipfile
sdk, version, key, alias, dest = sys.argv[1:]
sdk, key, out = Path(sdk), Path(key), Path(dest)
out.mkdir(); bt = sdk/'build-tools'/version
jar = sdk/'platforms'/'android-37.0'/'android.jar'
# javac/d8 are host tools, as are aapt and signing. No Alpine binary runs here.
def run(*args): sp.run(list(map(str,args)), check=True)
classes=out/'classes'; classes.mkdir(); dex=out/'dex'; dex.mkdir()
if os.environ.get('DEX_FILE'):
    shutil.copyfile(os.environ['DEX_FILE'], dex/'classes.dex')
else:
    run('javac','-source','8','-target','8','-cp',jar,'-d',classes,Path(__file__).with_name('FeatureActivity.java'))
    run('jar','cf',out/'classes.jar','-C',classes,'.')
    run(bt/'d8','--min-api','24','--output',dex,out/'classes.jar')

def apk(name, split=None, version=0, major=0, package='org.atl.tests.feature', code=False, requires=None, other_key=False):
    d=out/name; d.mkdir()
    attrs=f' split="{split}" android:isFeatureSplit="true"' if split else ''
    uses=f'<uses-split android:name="{requires}"/>' if requires else ''
    activity='' if split else '<activity android:name=".FeatureActivity" android:exported="true"><intent-filter><action android:name="android.intent.action.MAIN"/><category android:name="android.intent.category.LAUNCHER"/></intent-filter></activity>'
    (d/'AndroidManifest.xml').write_text(f'<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="{package}" android:versionCode="{version}" android:versionCodeMajor="{major}"{attrs}><uses-sdk android:minSdkVersion="24" android:targetSdkVersion="37"/>{uses}<application android:hasCode="{str(code).lower()}" android:debuggable="true">{activity}</application></manifest>')
    raw=d/'unsigned.apk'
    run(bt/'aapt','package','-f','-M',d/'AndroidManifest.xml','-I',jar,'-F',raw)
    if code:
        with zipfile.ZipFile(raw,'a') as z:
            z.write(dex/'classes.dex','classes.dex'); z.writestr('assets/feature.txt',b'*')
    result=out/(name+'.apk')
    run(bt/'apksigner','sign','--ks',out/'other.jks' if other_key else key,'--ks-key-alias','other' if other_key else alias,'--ks-pass','pass:android','--key-pass','pass:android','--out',result,raw)
    return result
run(os.environ.get('KEYTOOL','keytool'),'-genkeypair','-keystore',out/'other.jks','-storepass','android','-keypass','android','-alias','other','-dname','CN=ATL fixture only','-keyalg','RSA','-validity','30')
base=apk('base')
feature=apk('feature','feature',code=True)

def bundle(name, members):
    with zipfile.ZipFile(out/(name+'.apkm'),'w') as z:
        for i, member in enumerate(members): z.write(member,f'{i}.apk')
# Feature first proves manifest base selection and reordered resource/dex paths.
bundle('dexless-base',[feature,base])
bundle('version-zero-mismatch',[base,apk('v1','feature',version=1)])
bundle('major-mismatch',[base,apk('major1','feature',major=1)])
bundle('package-mismatch',[base,apk('other-package','feature',package='org.atl.tests.other')])
bundle('missing-dependency',[base,apk('dependent','feature',requires='absent')])
bundle('different-signer',[base,apk('different-key','feature',other_key=True)])
print(out)
