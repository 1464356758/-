from PIL import Image
import json
rows=json.load(open('tests/expected.json'))
for i,row in enumerate(rows):
 im=Image.open(f'tests/profile-{i}.jpg');e=im.getexif();x=e.get_ifd(34665)
 assert e[271]==row['manufacturer'] and e[272]==row['model']
 assert e[274]==1 and e[305]=='Camera Profile Studio 2.1'
 assert 'simulation' in e[270]
 assert x[40962]==120 and x[40963]==80 and x[40961]==1
 assert 34853 not in e and 37500 not in x and 34855 not in x and 33434 not in x
 for key,tag in [('aperture',33437),('focal_length',37386),('equivalent',41989)]:
  if key in row:assert abs(float(x[tag])-row[key])<0.001,(i,key)
  else:assert tag not in x
 if 'lens_model' in row:assert x[42036]==row['lens_model']
 assert 36867 not in x
 im.load()
print(f'PASS: independent Pillow readback of {len(rows)} outputs, dimensions, lenses, sRGB, privacy and simulation fields')
