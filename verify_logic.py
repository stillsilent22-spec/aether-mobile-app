import math

def clamp(v, lo, hi):
    return lo if v < lo else (hi if v > hi else v)

def verify_bridge():
    # ctx.x=0.5, ctx.y=0.3, ctx.z=0.1, ctx.t=0.2, ctx.i=3, ctx.n_val=8
    # ctx.mem = alle 0.0 außer mem[0]=1.5
    # node.iv = 5
    # a_val=12.0, d_val=7.0

    x, y, z, t, i, n_val = 0.5, 0.3, 0.1, 0.2, 3, 8
    iv = 5
    a_val, d_val = 12.0, 7.0
    mem = [0.0] * 256
    mem[0] = 1.5

    ua = clamp(abs(int(a_val)), 0, 255)
    ud = clamp(abs(int(d_val)), 0, 255)

    sa = (i + iv) & 7
    sb = (n_val + iv + 3) & 7

    # mix = ((ua << sa) ^ (ud >> sb) ^ (ua >> ((sb+1)&7)) ^ (ud << ((sa+1)&7))) & 255
    term1 = (ua << sa) & 255
    term2 = (ud >> sb) & 255
    term3 = (ua >> ((sb + 1) & 7)) & 255
    term4 = (ud << ((sa + 1) & 7)) & 255

    mix = (term1 ^ term2 ^ term3 ^ term4) & 255
    bit_mix = mix / 255.0

    syn = abs(a_val - d_val) * 0.06 + abs(bit_mix - y) * 0.32 + abs(z - t) * 0.02

    # mem[252] = ctx.mem[252]*0.78 + syn*0.22
    m252 = 0.0 * 0.78 + syn * 0.22
    m253 = 0.0 * 0.76 + (ua ^ mix) / 255.0 * 0.24
    m254 = 0.0 * 0.76 + (ud ^ mix) / 255.0 * 0.24

    pos = 3.0 / (8 - 1)
    phA = pos * 2 * math.pi
    phB = phA + (iv & 7) / 7.0 * math.pi

    bridge = (a_val * 0.22 + d_val * 0.18 + bit_mix * 0.34 + syn
              + mem[iv & 255] * 0.08 + m252 * 0.08
              + m253 * 0.06 + m254 * 0.04 + z * 0.02
              + (math.cos(phA) * a_val + math.cos(phB) * d_val) * 0.04)

    print(f"BRIDGE: mix={mix}, bit_mix={bit_mix}, syn={syn}, bridge={bridge}")

def verify_interfere():
    x, y, z, t, i, n_val = 0.5, 0.3, 0.1, 0.2, 3, 8
    iv = 5
    sA, sB = 10.0, 6.0

    pos = 3.0 / (8 - 1)
    ps = (iv & 15) / 15.0 * 2 * math.pi

    wA = sA * math.cos(pos * 2 * math.pi)
    wB = sB * math.cos(pos * 2 * math.pi + ps)

    ampA, ampB = abs(sA), abs(sB)
    result = clamp((wA + wB + ampA + ampB) * 127.5 / (ampA + ampB + 1e-9) + 127.5, 0.0, 255.0)

    print(f"INTERFERE: result={result}")

verify_bridge()
verify_interfere()
