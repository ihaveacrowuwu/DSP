# Active model artefacts

`active.onnx` is the served classifier: **effnetb0-0.1.0**, EfficientNet-B0
fine-tuned on the NOAA PIFSC ESD coral bleaching dataset. It is mounted read-only
into the ML container, so replacing it needs a service restart and no image
rebuild.

It is the one ML artefact that **is** committed, against the general rule in
`.gitignore` that artefacts are build outputs. `deploy/docker-compose.yml` runs the
service with `FAKE_MODE=0`, and onnxruntime raises `NoSuchFile` at import when the
artefact is absent - so a clone without it crash-loops the container and `make up`
never classifies anything. Committing 15 MB buys a repository that works on the
first try.

Other artefacts - checkpoints, alternative exports, training runs - stay ignored.

To serve a different model, drop it here as `active.onnx`, restart with
`make restart S=ml`, and record the version in the dashboard's Operations screen so
predictions cite the model that produced them. Set `FAKE_MODE=1` to fall back to
deterministic stubs, which need no artefact at all.
