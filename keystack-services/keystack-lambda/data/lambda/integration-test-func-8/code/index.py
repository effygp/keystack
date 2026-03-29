
import os
def handler(event, context):
    return {
        "MY_VAR": os.environ.get("MY_VAR"),
        "AWS_REGION": os.environ.get("AWS_REGION")
    }
