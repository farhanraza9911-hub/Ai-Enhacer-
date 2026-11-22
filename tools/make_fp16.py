import onnx, numpy as np, sys
from onnx import numpy_helper, helper, TensorProto
src, dst = sys.argv[1], sys.argv[2]
m = onnx.load(src)
g = m.graph
new_inits, new_nodes, n = [], [], 0
for t in g.initializer:
    if t.data_type == TensorProto.FLOAT and int(np.prod(t.dims)) >= 256:
        a = numpy_helper.to_array(t)
        h = numpy_helper.from_array(a.astype(np.float16), t.name + "__h")
        new_inits.append(h)
        new_nodes.append(helper.make_node("Cast", [h.name], [t.name], to=TensorProto.FLOAT, name="cast_" + t.name))
        n += 1
    else:
        new_inits.append(t)
del g.initializer[:]
g.initializer.extend(new_inits)
nodes = list(g.node)
del g.node[:]
g.node.extend(new_nodes + nodes)   # casts first (topological order)
onnx.checker.check_model(m)
onnx.save(m, dst)
print("converted", n, "initializers")
