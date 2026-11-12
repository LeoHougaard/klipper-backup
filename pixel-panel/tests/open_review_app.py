"""Open the installed review app using Android's Home chooser, without defaults."""
import time
import re
from check_controls import SERIAL, adb, snapshot, tap


def main():
    assert SERIAL and SERIAL.startswith('emulator-'), 'Review emulator required'
    for _ in range(3):
        adb('shell','input','keyevent','3')
        root = snapshot()
        if any(n.get('content-desc','').startswith('Screen:') for n in root.iter('node')):
            print('Review app visible',flush=True)
            return
        if any(n.get('text','').startswith('Use ') and n.get('text','').endswith(' as Home') for n in root.iter('node')):
            # Android may highlight Quickstep after replacing the APK. Select
            # the visible Voron row before choosing Just once, never Always.
            alternative = next((n for n in root.iter('node') if n.get('text') == 'Voron'),None)
            if alternative is not None:
                x,y,r,b = map(int,re.findall(r'\d+',alternative.get('bounds')))
                adb('shell','input','tap',str((x+r)//2),str((y+b)//2))
                root = snapshot()
        if any(n.get('text') == 'Use Voron as Home' for n in root.iter('node')):
            tap('Just once', root)
            time.sleep(1)
            root = snapshot()
            if any(n.get('content-desc','').startswith('Screen:') for n in root.iter('node')):
                print('Review app visible; launcher default preserved',flush=True)
                return
    raise RuntimeError('Review app did not open through the Home chooser')


if __name__ == '__main__': main()
