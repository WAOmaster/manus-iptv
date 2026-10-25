# Reports PT_LOAD p_align of each ELF .so; non-zero exit if any is below 16384 (Play 16 KB page-size rule for 64-bit libs).
import struct,sys
bad=0
for f in sys.argv[1:]:
    d=open(f,'rb').read(); assert d[:4]==b'\x7fELF'
    is64=d[4]==2
    if is64: phoff,=struct.unpack_from('<Q',d,32); phes,phn=struct.unpack_from('<HH',d,54)
    else: phoff,=struct.unpack_from('<I',d,28); phes,phn=struct.unpack_from('<HH',d,42)
    al=[]
    for i in range(phn):
        o=phoff+i*phes
        t,=struct.unpack_from('<I',d,o)
        if t==1: al.append(struct.unpack_from('<Q',d,o+48)[0] if is64 else struct.unpack_from('<I',d,o+28)[0])
    ok=min(al)>=16384; bad+=not ok
    print(f, al, 'OK 16K' if ok else 'FAIL <16K')

sys.exit(1 if bad else 0)
