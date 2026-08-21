# Custom task examples

Task Builder JSON files are the editable source examples. Import them from Task
Builder to inspect, execute, change, save, and generate Java. Generated Java is
runtime output and is not kept as a duplicate of an editable JSON example.

- `expert_idle_exploration.json` is an editable end-to-end example.
- `dead_shot.json` is an incomplete tutorial and regression flow for custom
  template files; read `dead_shot.txt` before running it.
- `shield.java` is intentionally retained as a hand-written custom task because
  it demonstrates scheduling and `CustomTaskConfigurable` behavior that Task
  Builder JSON cannot represent.

Keep each JSON file together with any relative template files when sharing it.
The editor copies newly selected template images into the workspace's
`custom-tasks/templates` directory and stores a relative path.
