# Active model artefacts

Drop a trained `active.onnx` here and set `FAKE_MODE=0` on the ML service to
serve real predictions. The file is mounted read-only into the container, so no
image rebuild is needed - restart the service to load a new artefact.

Artefacts are **not** committed (see `.gitignore`); they are build outputs of
`ml/training`. Record each version in the admin Operations screen so predictions
cite the model that produced them.
