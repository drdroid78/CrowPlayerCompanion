from pathlib import Path
from PIL import Image, ImageDraw, ImageFont
import sys
p=Path(sys.argv[1])
for f in p.glob('*.ppm'):
    image=Image.open(f).convert('RGBA')
    mask=Image.new('L',(240,240)); ImageDraw.Draw(mask).ellipse((0,0,239,239),fill=255)
    image.putalpha(mask); image.save(f.with_suffix('.png'))
font=ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf',22)
out=Image.new('RGB',(1120,790),'#101218'); d=ImageDraw.Draw(out)
names=['Дежурный экран','Музыка','Звук','Источники','Настройки','Темы']
for s,name in enumerate(names):
    x=40+(s%3)*360; y=25+(s//3)*375
    image=Image.open(p/f'theme-1-screen-{s}.png').resize((300,300),Image.Resampling.LANCZOS)
    out.paste(image,(x,y),image);d.text((x+150,y+317),name,font=font,fill='#e0e3e8',anchor='mt')
out.save(p/'interface.png')
