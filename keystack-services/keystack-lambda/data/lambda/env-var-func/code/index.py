import os
def handler(event, context):
    return {"val": os.environ.get("MY_VAR")}