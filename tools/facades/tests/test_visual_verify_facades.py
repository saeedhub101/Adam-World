import importlib.util, unittest
from pathlib import Path
import cv2, numpy as np
SCRIPT=Path(__file__).resolve().parents[1]/"visual_verify_facades.py"
spec=importlib.util.spec_from_file_location("visual_verify_facades",SCRIPT)
mod=importlib.util.module_from_spec(spec); spec.loader.exec_module(mod)
class VisualScreenTests(unittest.TestCase):
 def test_synthetic_structured_image_is_measured_without_identity_claim(self):
  img=np.full((480,640,3),180,np.uint8)
  for x in range(80,600,35): cv2.line(img,(x,40),(x,450),(20,20,20),3)
  for y in range(100,430,75): cv2.line(img,(50,y),(610,y),(40,40,40),2)
  ok,buf=cv2.imencode(".jpg",img)
  result=mod.analyze_image(buf.tobytes())
  self.assertIn("visual_quality_score",result)
  self.assertIn("facade_line_cues",result["checks"])
  self.assertNotIn("building_identity_verified",result)
 def test_invalid_bytes_rejected(self):
  with self.assertRaises(ValueError): mod.analyze_image(b"not an image")
if __name__=="__main__": unittest.main()
