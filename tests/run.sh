#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p tests/build/pure
javac -d tests/build/pure app/src/com/cameraprofile/studio/{JpegEngine,JpegFiles,ExifReader,PixelReconstructor,PixelOrientation,ResolutionPlan,MemoryBudget,NeuralTiles,Io}.java tests/{EngineTest,OrientationTest,ReconstructionTest,ProfilesTest,ReconstructionPipelineTest,ContainerTest,NeuralTilesTest,CredentialTest}.java
java -cp tests/build/pure EngineTest tests/input.jpg tests/output.jpg
for test_name in OrientationTest ReconstructionTest ProfilesTest ReconstructionPipelineTest ContainerTest NeuralTilesTest CredentialTest; do
  java -Xmx1g -cp tests/build/pure "$test_name"
done
python3 tests/verify_profiles.py
